package lab.aclab.shield;

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

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps the index current and reveals a container once it becomes visible.
 *
 * <p>Hiding alone is not enough: when a player digs through to a chest the
 * server only sends an update for the broken block, so the chest stays stone on
 * the client and the player cannot see their own container. Every block change
 * therefore re-evaluates its neighbours; a position that became visible is
 * dropped from the index and the real block is sent to nearby players.
 */
public final class ShieldIndexer implements Listener {


    private static final BlockFace[] SIX = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
            BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final Plugin plugin;
    private final HiddenIndex index;

    /**
     * Block types to hide.
     *
     * <p>A base is not only chests: a furnace, a hopper or a beacon says
     * "someone lives here" just as plainly. The list comes from configuration,
     * because which blocks count as revealing depends on how a server plays.
     */
    private final Set<org.bukkit.Material> protectedTypes;

    /** Maximum distance, in blocks, a reveal is sent to. */
    private final double revealRange;

    /** Chunk radius around a player covered by the safety-net sweep. */
    private final int sweepRadius;

    public ShieldIndexer(Plugin plugin, HiddenIndex index, double revealRange, int sweepRadius,
                         Set<org.bukkit.Material> protectedTypes) {
        this.plugin = plugin;
        this.index = index;
        this.protectedTypes = protectedTypes;
        this.revealRange = revealRange;
        this.sweepRadius = sweepRadius;
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
                + index.size() + " buried block entities");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        indexChunk(event.getChunk());
        // Blocks on a border may have neighbours in this chunk; refresh those too.
        World world = event.getWorld();
        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            if (world.isChunkLoaded(cx + o[0], cz + o[1])) {
                indexChunk(world.getChunkAt(cx + o[0], cz + o[1]));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        index.clearChunk(event.getChunk().getX(), event.getChunk().getZ());
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

    /**
     * Periodically re-evaluates hidden entries near players.
     *
     * <p>Events cannot catch every change: a chest opened by a command, a plugin,
     * WorldEdit, a piston or flowing water produces no {@code BlockBreakEvent}.
     * A chest left hidden by mistake means a player loses their items, so a
     * safety net is needed alongside events. The entry count is small, which
     * makes this sweep cheap.
     */
    private final java.util.concurrent.atomic.AtomicLong sweeps =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong sweepNanos =
            new java.util.concurrent.atomic.AtomicLong();

    /** Time spent on the main thread - this is the part that affects TPS. */
    public String stats() {
        long n = sweeps.get();
        return String.format(java.util.Locale.ROOT,
                "sweep(main): %d runs, %.2f ms/run average, %d entries indexed",
                n, n == 0 ? 0.0 : sweepNanos.get() / 1_000_000.0 / n, index.size());
    }

    public void resetStats() {
        sweeps.set(0);
        sweepNanos.set(0);
    }

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
        var players = plugin.getServer().getOnlinePlayers();
        if (players.isEmpty()) {
            return;
        }
        java.util.Set<Long> done = new java.util.HashSet<>();
        for (var player : players) {
            World world = player.getWorld();
            int pcx = player.getLocation().getBlockX() >> 4;
            int pcz = player.getLocation().getBlockZ() >> 4;

            for (int dx = -sweepRadius; dx <= sweepRadius; dx++) {
                for (int dz = -sweepRadius; dz <= sweepRadius; dz++) {
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    if (!done.add(HiddenIndex.chunkKey(cx, cz)) || !world.isChunkLoaded(cx, cz)) {
                        continue;
                    }
                    indexChunk(world.getChunkAt(cx, cz));
                }
            }
        }
    }

    /** The change lands after the event returns, so wait one tick. */
    private void scheduleReevaluate(Block block) {
        Location loc = block.getLocation();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            reevaluate(loc.getBlock());
            for (BlockFace face : SIX) {
                reevaluate(loc.getBlock().getRelative(face));
            }
        });
    }

    /** Re-decides whether a single position should be hidden. */
    private void reevaluate(Block block) {
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        boolean shouldHide = protectedTypes.contains(block.getType()) && enclosed(block);

        if (shouldHide) {
            index.hide(x, y, z);
            return;
        }
        if (index.reveal(x, y, z)) {
            revealToPlayers(block);
        }
    }

    private void indexChunk(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities(false)) {
            Block block = state.getBlock();
            if (protectedTypes.contains(block.getType())) {
                reevaluate(block);
            }
        }
    }

    /**
     * A block is buried when all six neighbours block light.
     *
     * <p>No decision is made for an unloaded neighbour chunk and the block stays
     * visible: hiding one by mistake means a player loses their own chest, and
     * the position is re-evaluated anyway once that chunk loads.
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

    /** Sends the real block to nearby players once it becomes visible. */
    private void revealToPlayers(Block block) {
        Location loc = block.getLocation();
        var data = block.getBlockData();
        BlockState state = block.getState();
        List<Player> players = block.getWorld().getPlayers();

        for (Player player : players) {
            if (player.getLocation().distance(loc) > revealRange) {
                continue;
            }
            player.sendBlockChange(loc, data);
            if (state instanceof TileState tileState) {
                player.sendBlockUpdate(loc, tileState);
            }
        }
        plugin.getLogger().info(String.format(Locale.ROOT,
                "shield: %d,%d,%d is now visible - real block sent",
                block.getX(), block.getY(), block.getZ()));
    }
}
