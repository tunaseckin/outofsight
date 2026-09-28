package io.github.tunaseckin.outofsight.shield;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.NumberConversions;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the index current and sends containers to players who come near them.
 *
 * <p>Containers are withheld from chunk packets while a player is far away, so
 * something has to deliver them once that player approaches. Nothing resends a
 * chunk on movement, which is why this runs on a timer instead.
 *
 * <p>Enclosure is tracked separately and only adds a reason to keep hiding. When
 * a player breaks the wall around a sealed chest, the block change re-evaluates
 * its neighbours and the chest is sent immediately rather than waiting a tick.
 */
public final class ShieldIndexer implements Listener {

    private static final BlockFace[] SIX = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
            BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    /** Held by players the shield applies to while test mode is on. */
    public static final String SHIELDED_PERMISSION = "outofsight.shielded";

    /** Held by staff who should see every container, shield or not. */
    public static final String BYPASS_PERMISSION = "outofsight.bypass";

    /** Whether the shield applies to this player, before looking at the world. */
    public static boolean covers(Player player, boolean testMode) {
        if (player.hasPermission(BYPASS_PERMISSION)) {
            return false;
        }
        return !testMode || player.hasPermission(SHIELDED_PERMISSION);
    }

    private final Plugin plugin;
    private final HiddenIndex index;
    private final int sweepRadius;
    private final boolean testMode;

    /**
     * Block types to hide. A base is not only chests: a furnace, a hopper or a
     * beacon says "someone lives here" just as plainly.
     */
    private final Set<org.bukkit.Material> protectedTypes;

    /** Beyond this many blocks a container is left out of the packet. */
    private final double hideBeyond;
    private final long hideBeyondSq;

    /**
     * Bumped whenever a block changes, so a standing player can be skipped.
     *
     * <p>Line of sight only changes when the player moves or the world does.
     * Without this, a container that stays out of sight is re-traced several
     * times a second forever, which is the bulk of the work and none of the value.
     */
    private final java.util.concurrent.atomic.AtomicLong worldRevision =
            new java.util.concurrent.atomic.AtomicLong();

    /** Last position and world revision each player was evaluated against. */
    private final Map<UUID, long[]> lastCheck = new ConcurrentHashMap<>();

    /**
     * The same for delivery: eye position, world revision and index version.
     *
     * <p>When none of them changed, every container this player could be given
     * has already been judged, so the rays would only repeat their answers.
     */
    private final Map<UUID, long[]> lastSweep = new ConcurrentHashMap<>();

    private final java.util.concurrent.atomic.AtomicLong sweeps =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong sweepNanos =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong sent =
            new java.util.concurrent.atomic.AtomicLong();

    public ShieldIndexer(Plugin plugin, HiddenIndex index, int sweepRadius,
                         double hideBeyond, Set<org.bukkit.Material> protectedTypes,
                         boolean testMode) {
        this.plugin = plugin;
        this.index = index;
        this.testMode = testMode;
        this.sweepRadius = sweepRadius;
        this.hideBeyond = hideBeyond;
        this.hideBeyondSq = (long) (hideBeyond * hideBeyond);
        this.protectedTypes = protectedTypes;
    }

    /** Time spent on the main thread, which is the part that affects TPS. */
    public String stats() {
        long n = sweeps.get();
        return String.format(Locale.ROOT,
                "sweep(main): %d runs, %.2f ms/run average, %d indexed, %d containers delivered",
                n, n == 0 ? 0.0 : sweepNanos.get() / 1_000_000.0 / n, index.size(), sent.get());
    }

    public void resetStats() {
        sweeps.set(0);
        sweepNanos.set(0);
        sent.set(0);
    }

    /** Scans chunks that are already loaded when the plugin starts. */
    public void indexLoadedChunks() {
        int chunks = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                indexChunk(chunk);
                chunks++;
            }
        }
        plugin.getLogger().info("shield index: scanned " + chunks + " chunks, "
                + index.size() + " containers");
    }

    // --- events -----------------------------------------------------------

    /**
     * Records the permission before the first chunk goes out.
     *
     * <p>The sweep would catch up a fraction of a second later, but the first
     * chunk burst is exactly the part an admin is testing. LOW, so it runs after
     * the LOWEST handler that grants {@code /outofsight testme}'s permission.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        index.setShielded(event.getPlayer().getUniqueId(), covers(event.getPlayer(), testMode));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        indexChunk(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        index.clearChunk(event.getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ());
    }

    /** What was delivered in the old world says nothing about the new one. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChangedWorld(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        index.clearDelivered(event.getPlayer().getUniqueId());
        lastCheck.remove(event.getPlayer().getUniqueId());
        lastSweep.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        scheduleReevaluate(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        scheduleReevaluate(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(this::scheduleReevaluate);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(this::scheduleReevaluate);
    }

    /** The change lands after the event returns, so wait one tick. */
    private void scheduleReevaluate(Block block) {
        worldRevision.incrementAndGet();
        Location loc = block.getLocation();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            reevaluate(loc.getBlock());
            for (BlockFace face : SIX) {
                reevaluate(loc.getBlock().getRelative(face));
            }
        });
    }

    // --- indexing ---------------------------------------------------------

    /**
     * Brings the index in line with what a chunk actually holds.
     *
     * <p>Events miss plenty: a command, WorldEdit, a piston or flowing water can
     * add or remove a container without firing anything. Re-reading the chunk's
     * block entities covers both directions, and it is cheap because a chunk
     * holds few of them.
     */
    private void indexChunk(Chunk chunk) {
        UUID world = chunk.getWorld().getUID();
        Set<Long> present = new HashSet<>();
        for (BlockState state : chunk.getTileEntities(false)) {
            Block block = state.getBlock();
            if (protectedTypes.contains(block.getType())) {
                present.add(HiddenIndex.posKey(block.getX(), block.getY(), block.getZ()));
                reevaluate(block);
            }
        }
        for (long pos : Set.copyOf(index.containersIn(world, chunk.getX(), chunk.getZ()))) {
            if (!present.contains(pos)) {
                index.removeContainer(world, HiddenIndex.posXOf(pos), HiddenIndex.posYOf(pos),
                        HiddenIndex.posZOf(pos));
            }
        }
    }

    /** Re-decides what the index knows about one position. */
    private void reevaluate(Block block) {
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        UUID world = block.getWorld().getUID();

        if (!protectedTypes.contains(block.getType())) {
            if (index.isContainer(world, x, y, z)) {
                index.removeContainer(world, x, y, z);
            }
            return;
        }
        index.addContainer(world, x, y, z);

        boolean nowEnclosed = enclosed(block);
        boolean changed = index.setEnclosed(world, x, y, z, nowEnclosed);
        if (changed) {
            worldRevision.incrementAndGet(); // Standing players need a fresh look.
        }
        if (changed && !nowEnclosed) {
            // A wall came down. Deliver it now instead of waiting for the sweep.
            deliver(block);
        }
    }

    /**
     * A block is buried when all six neighbours block light.
     *
     * <p>No decision is made for an unloaded neighbour chunk and the block is
     * treated as visible: hiding one by mistake means a player loses their own
     * chest, and the position is re-evaluated once that chunk loads.
     */
    private boolean enclosed(Block block) {
        World world = block.getWorld();
        for (BlockFace face : SIX) {
            Block neighbour = block.getRelative(face);
            if (neighbour.getY() < world.getMinHeight() || neighbour.getY() >= world.getMaxHeight()) {
                continue;
            }
            if (!world.isChunkLoaded(neighbour.getX() >> 4, neighbour.getZ() >> 4)) {
                return false;
            }
            if (!neighbour.getType().isOccluding()) {
                return false;
            }
        }
        return true;
    }

    // --- delivery ---------------------------------------------------------

    /**
     * Finds containers events did not report. Runs on the slower timer, because
     * a container appearing a second late costs nothing.
     */
    public void discover() {
        // Keyed by world too: two players at the same chunk coordinates in
        // different dimensions must both get their chunk re-read.
        Set<String> reindexed = new HashSet<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Location loc = player.getLocation();
            long posKey = HiddenIndex.posKey(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            long revision = worldRevision.get();
            long[] last = lastCheck.get(player.getUniqueId());
            if (last != null && last[0] == posKey && last[1] == revision) {
                continue; // Nothing moved and nothing changed.
            }
            lastCheck.put(player.getUniqueId(), new long[]{posKey, revision});

            World world = player.getWorld();
            int rcx = loc.getBlockX() >> 4;
            int rcz = loc.getBlockZ() >> 4;
            for (int dx = -sweepRadius; dx <= sweepRadius; dx++) {
                for (int dz = -sweepRadius; dz <= sweepRadius; dz++) {
                    int cx = rcx + dx;
                    int cz = rcz + dz;
                    if (reindexed.add(world.getUID() + ":" + HiddenIndex.chunkKey(cx, cz))
                            && world.isChunkLoaded(cx, cz)) {
                        indexChunk(world.getChunkAt(cx, cz));
                    }
                }
            }
        }
    }

    /** Most queued looks handled per tick, so a large burst cannot stall one. */
    private static final int LOOKS_PER_TICK = 256;

    /**
     * Checks containers a chunk packet just withheld. Runs every tick.
     *
     * <p>This sends what the player can see without recording it as delivered.
     * The block update can overtake the chunk packet it belongs to, and the
     * client then drops it; recording it would leave that chest invisible. So the
     * player's next sweep is forced to run, and the sweep does the bookkeeping.
     */
    public void lookSoon() {
        for (int i = 0; i < LOOKS_PER_TICK; i++) {
            HiddenIndex.Look look = index.pollLook();
            if (look == null) {
                return;
            }
            Player player = plugin.getServer().getPlayer(look.player());
            if (player == null || !player.getWorld().getUID().equals(look.world())) {
                continue;
            }
            int x = HiddenIndex.posXOf(look.pos());
            int y = HiddenIndex.posYOf(look.pos());
            int z = HiddenIndex.posZOf(look.pos());
            if (!index.isContainer(look.world(), x, y, z) || index.isEnclosed(look.world(), x, y, z)
                    || index.isDelivered(look.player(), x, y, z)) {
                continue;
            }
            Location loc = player.getLocation();
            double distSq = NumberConversions.square(loc.getBlockX() - x)
                    + NumberConversions.square(loc.getBlockY() - y)
                    + NumberConversions.square(loc.getBlockZ() - z);
            if (distSq > hideBeyondSq) {
                continue;
            }
            Block block = player.getWorld().getBlockAt(x, y, z);
            if (hasLineOfSight(player, block)) {
                deliver(block, player);
                lastSweep.remove(look.player());
            }
        }
    }

    /**
     * Delivers containers a player has come close enough to see. Runs on the
     * faster timer: walking into a room and waiting for the chests to appear is
     * exactly the kind of thing a player would notice.
     */
    public void sweep() {
        long started = System.nanoTime();
        try {
            doSweep();
        } finally {
            sweeps.incrementAndGet();
            sweepNanos.addAndGet(System.nanoTime() - started);
        }
    }

    private void doSweep() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            index.setShielded(player.getUniqueId(), covers(player, testMode));
            if (!index.isShielded(player.getUniqueId())
                    || !index.isWorldShielded(player.getWorld().getName())) {
                continue; // Packets to this player are left alone; nothing to deliver.
            }
            Location eye = player.getEyeLocation();
            long[] state = {
                    HiddenIndex.posKey(eye.getBlockX(), eye.getBlockY(), eye.getBlockZ()),
                    worldRevision.get(), index.version(),
                    player.getWorld().getUID().getMostSignificantBits()};
            if (java.util.Arrays.equals(state, lastSweep.put(player.getUniqueId(), state))) {
                continue; // Nothing moved and nothing changed.
            }
            Location loc = player.getLocation();
            World world = player.getWorld();
            UUID worldId = world.getUID();

            int pcx = loc.getBlockX() >> 4;
            int pcz = loc.getBlockZ() >> 4;
            Set<Long> stillNear = new HashSet<>();

            for (int dx = -sweepRadius; dx <= sweepRadius; dx++) {
                for (int dz = -sweepRadius; dz <= sweepRadius; dz++) {
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    if (!world.isChunkLoaded(cx, cz)) {
                        continue;
                    }
                    for (long pos : index.containersIn(worldId, cx, cz)) {
                        int x = HiddenIndex.posXOf(pos);
                        int y = HiddenIndex.posYOf(pos);
                        int z = HiddenIndex.posZOf(pos);

                        double distSq = NumberConversions.square(loc.getBlockX() - x)
                                + NumberConversions.square(loc.getBlockY() - y)
                                + NumberConversions.square(loc.getBlockZ() - z);
                        if (distSq > hideBeyondSq || index.isEnclosed(worldId, x, y, z)) {
                            continue;
                        }
                        Block block = world.getBlockAt(x, y, z);
                        boolean known = index.isDelivered(player.getUniqueId(), x, y, z);
                        if (!known) {
                            // Enclosed blocks cannot have a line of sight, so the
                            // cheap test spares the expensive one.
                            if (index.isEnclosed(worldId, x, y, z) || !hasLineOfSight(player, block)) {
                                continue;
                            }
                        }
                        stillNear.add(pos);
                        if (index.markDelivered(player.getUniqueId(), pos)) {
                            deliver(block, player);
                        }
                    }
                }
            }
            // Forget the ones left behind, so they are hidden again on a resend.
            index.retainDelivered(player.getUniqueId(), stillNear);
        }
    }

    /**
     * Whether the player has an unobstructed line to the container.
     *
     * <p>Distance alone lets someone standing on a hill collect the contents of a
     * base buried under it. A line of sight test answers the question that
     * actually matters, which is whether this player could see the thing.
     *
     * <p>The cost is bounded because it only runs for containers not yet
     * delivered. Walking into a base with fifty chests pays for fifty rays once,
     * then nothing. A per tick, per player, per container ray would not be
     * affordable.
     */
    private boolean hasLineOfSight(Player player, Block block) {
        Location eye = player.getEyeLocation();
        // The centre first, which settles almost every case with one ray.
        if (rayReaches(eye, block, 0.5, 0.5, 0.5)) {
            return true;
        }
        // Then the middle of each face turned towards the player. A chest in a
        // recess shows its front while its centre is behind the wall edge.
        double ex = eye.getX() - block.getX();
        double ey = eye.getY() - block.getY();
        double ez = eye.getZ() - block.getZ();
        if ((ex < 0 || ex > 1) && rayReaches(eye, block, ex < 0 ? 0.05 : 0.95, 0.5, 0.5)) {
            return true;
        }
        if ((ey < 0 || ey > 1) && rayReaches(eye, block, 0.5, ey < 0 ? 0.05 : 0.95, 0.5)) {
            return true;
        }
        return (ez < 0 || ez > 1) && rayReaches(eye, block, 0.5, 0.5, ez < 0 ? 0.05 : 0.95);
    }

    /**
     * Whether a ray from the eye to a point inside the block gets there.
     *
     * <p>Stopping on a protected block right next to the target also counts.
     * Looked at end-on, the ray to the far half of a double chest or a bed hits
     * the near half first; treating that as blocked left players looking at
     * half a chest.
     */
    private boolean rayReaches(Location eye, Block block, double ox, double oy, double oz) {
        Location target = block.getLocation().add(ox, oy, oz);
        org.bukkit.util.Vector direction = target.toVector().subtract(eye.toVector());
        double distance = direction.length();
        if (distance < 0.1) {
            return true;
        }
        var hit = eye.getWorld().rayTraceBlocks(eye, direction.normalize(), distance,
                org.bukkit.FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitBlock() == null) {
            return true;
        }
        Block hitBlock = hit.getHitBlock();
        if (hitBlock.equals(block)) {
            return true;
        }
        int apart = Math.abs(hitBlock.getX() - block.getX())
                + Math.abs(hitBlock.getY() - block.getY())
                + Math.abs(hitBlock.getZ() - block.getZ());
        return apart == 1 && protectedTypes.contains(hitBlock.getType());
    }

    /** Sends a container's real block and block entity data to nearby players. */
    private void deliver(Block block) {
        for (Player player : block.getWorld().getPlayers()) {
            if (player.getLocation().distance(block.getLocation()) <= hideBeyond) {
                deliver(block, player);
            }
        }
    }

    private void deliver(Block block, Player player) {
        Location loc = block.getLocation();
        player.sendBlockChange(loc, block.getBlockData());
        BlockState state = block.getState();
        if (state instanceof TileState tileState) {
            player.sendBlockUpdate(loc, tileState);
        }
        sent.incrementAndGet();
    }

    public void forget(Player player) {
        index.forgetPlayer(player.getUniqueId());
        lastCheck.remove(player.getUniqueId());
        lastSweep.remove(player.getUniqueId());
    }
}
