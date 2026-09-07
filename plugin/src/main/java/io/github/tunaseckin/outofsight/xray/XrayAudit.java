package io.github.tunaseckin.outofsight.xray;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compares outgoing chunk packets against the world's real contents.
 *
 * <p>This is an <em>audit</em>, not a detector. Information cheats such as X-ray
 * and Block ESP break no rule of physics: the server already sent the ore's
 * position and the cheat merely draws it. Nothing in the packet stream is
 * anomalous, so these cheats cannot be detected. The only real defence is not
 * sending the data - and this class measures whether it was sent.
 *
 * <p>A packet carries two separate channels and <em>both</em> must be audited:
 * <ul>
 *   <li>Block states - what anti-xray obfuscates.</li>
 *   <li>The block entity list - chests and spawners report their positions here
 *       <em>as well</em>. Even with the block data hidden this list can stay
 *       exposed, so it is counted separately.</li>
 * </ul>
 */
public final class XrayAudit extends PacketListenerAbstract {

    /** Blocks targeted by base finding and X-ray. */
    private static final Set<String> SENSITIVE = Set.of(
            "diamond_ore", "deepslate_diamond_ore",
            "emerald_ore", "deepslate_emerald_ore",
            "gold_ore", "deepslate_gold_ore",
            "iron_ore", "deepslate_iron_ore",
            "lapis_ore", "deepslate_lapis_ore",
            "redstone_ore", "deepslate_redstone_ore",
            "ancient_debris",
            "chest", "trapped_chest", "ender_chest", "barrel",
            "spawner", "budding_amethyst");

    /** These also report their position in the block entity list. */
    private static final Set<String> BLOCK_ENTITIES = Set.of(
            "chest", "trapped_chest", "ender_chest", "barrel", "spawner");

    /** Matches anti-xray's upper bound; scanning above it is pointless. */
    private final int maxScanY;

    private final Plugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /**
     * A watch bound to a <em>position</em> rather than a player.
     *
     * <p>The same chunk is audited whoever it is sent to, and not just once, so
     * verification can be repeated with a headless test client instead of
     * waiting for a person to relog.
     */
    private volatile Watch globalWatch;

    public XrayAudit(Plugin plugin, int maxScanY) {
        super(PacketListenerPriority.MONITOR);
        this.plugin = plugin;
        this.maxScanY = maxScanY;
    }

    public void start(Player player, int chunkBudget) {
        sessions.put(player.getUniqueId(),
                new Session(chunkBudget, player.getWorld().getMinHeight()));
    }

    public void stop(Player player) {
        sessions.remove(player.getUniqueId());
    }

    /**
     * Watches one position and reports when that chunk is sent.
     *
     * <p>Sampling-based auditing contains a race: if the chunk burst finishes
     * before the audit is started, the target chunk never enters the sample. A
     * watch removes that - the right chunk is caught whatever the order. It
     * survives disconnect, because the test requires relogging.
     */
    public void watch(org.bukkit.World world, java.util.List<WatchPos> positions) {
        WatchPos first = positions.get(0);
        globalWatch = new Watch(first.x() >> 4, first.z() >> 4, world.getMinHeight(), positions);
    }

    public void clearWatch() {
        globalWatch = null;
    }

    /** One watched position: expected block name and report label. */
    public record WatchPos(int x, int y, int z, String expected, String label) {
    }

    private record Watch(int chunkX, int chunkZ, int minY, java.util.List<WatchPos> positions) {
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.CHUNK_DATA) {
            return;
        }
        UUID id = event.getUser().getUUID();
        if (id == null) {
            return;
        }
        Session session = sessions.get(id);
        Watch watch = globalWatch;
        boolean wantSession = session != null && session.claimChunk();
        if (!wantSession && watch == null) {
            return;
        }

        // The packet must be decoded on the network thread - the buffer is only
        // valid here. The decoded data can be handed off safely afterwards.
        Column column = new WrapperPlayServerChunkData(event).getColumn();
        int chunkX = column.getX();
        int chunkZ = column.getZ();

        if (watch != null && chunkX == watch.chunkX() && chunkZ == watch.chunkZ()) {
            reportWatch(id, watch, column);
        }
        if (!wantSession) {
            return;
        }

        Map<Long, String> packetBlocks = scanPacket(column, session.minY);
        Set<Long> packetTileEntities = scanTileEntities(column);

        plugin.getServer().getScheduler().runTask(plugin, () -> compareAgainstWorld(
                id, chunkX, chunkZ, packetBlocks, packetTileEntities, session));
    }

    /** Collects the positions and types of sensitive blocks visible in the packet. */
    private Map<Long, String> scanPacket(Column column, int minY) {
        Map<Long, String> found = new HashMap<>();
        BaseChunk[] sections = column.getChunks();

        for (int i = 0; i < sections.length; i++) {
            BaseChunk section = sections[i];
            if (section == null || section.isEmpty()) {
                continue;
            }
            int baseY = minY + i * 16;
            if (baseY > maxScanY) {
                break;
            }
            for (int y = 0; y < 16; y++) {
                int worldY = baseY + y;
                if (worldY > maxScanY) {
                    break;
                }
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        WrappedBlockState state = section.get(x, y, z);
                        if (state == null) {
                            continue;
                        }
                        String name = stripNamespace(state.getType().getName());
                        if (SENSITIVE.contains(name)) {
                            found.put(key(x, worldY, z), name);
                        }
                    }
                }
            }
        }
        return found;
    }

    /**
     * Positions in the packet's block entity list.
     *
     * <p>This list is independent of block obfuscation: even with the chest's
     * block turned to stone, a record left here still gives the position away.
     */
    private Set<Long> scanTileEntities(Column column) {
        Set<Long> positions = new HashSet<>();
        TileEntity[] tiles = column.getTileEntities();
        if (tiles == null) {
            return positions;
        }
        for (TileEntity tile : tiles) {
            if (tile != null) {
                positions.add(key(tile.getX() & 0xF, tile.getY(), tile.getZ() & 0xF));
            }
        }
        return positions;
    }

    private void compareAgainstWorld(UUID playerId, int chunkX, int chunkZ,
                                     Map<Long, String> packetBlocks,
                                     Set<Long> packetTileEntities, Session session) {
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null) {
            return;
        }
        World world = player.getWorld();
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }
        ChunkSnapshot snap = world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(false, false, false);

        int minY = world.getMinHeight();
        int topY = Math.min(maxScanY, world.getMaxHeight() - 1);

        int real = 0;
        int leaked = 0;
        int leakedExposed = 0;
        int leakedBuried = 0;
        int wrongType = 0;
        int tileLeak = 0;
        int tileReal = 0;
        int tileExposed = 0;
        int tileBuried = 0;
        Set<Long> realPositions = new HashSet<>();
        Map<String, Integer> buriedTypes = new HashMap<>();

        for (int y = minY; y <= topY; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Material m = snap.getBlockType(x, y, z);
                    String name = m.name().toLowerCase(Locale.ROOT);
                    if (!SENSITIVE.contains(name)) {
                        continue;
                    }
                    real++;
                    long k = key(x, y, z);
                    realPositions.add(k);

                    // The block entity channel: the position can leak here even
                    // with the block hidden. A fully buried chest is a hidden
                    // base, so this count is exactly what base finding relies on.
                    if (BLOCK_ENTITIES.contains(name)) {
                        tileReal++;
                        if (packetTileEntities.contains(k)) {
                            tileLeak++;
                            if (isExposed(world, (chunkX << 4) + x, y, (chunkZ << 4) + z)) {
                                tileExposed++;
                            } else {
                                tileBuried++;
                            }
                        }
                    }

                    String packetName = packetBlocks.get(k);
                    if (packetName == null) {
                        continue; // No sensitive block in the packet - hidden.
                    }
                    if (!packetName.equals(name)) {
                        // A different ore shows in its place: obfuscation worked.
                        wrongType++;
                        continue;
                    }
                    leaked++;
                    // Hiding an exposed block would break the world: the player
                    // can already see it. The real problem is a buried leak.
                    if (isExposed(world, (chunkX << 4) + x, y, (chunkZ << 4) + z)) {
                        leakedExposed++;
                    } else {
                        leakedBuried++;
                        buriedTypes.merge(name, 1, Integer::sum);
                    }
                }
            }
        }

        // Fake: sensitive in the packet but not in the world - anti-xray noise.
        int fake = 0;
        for (long pos : packetBlocks.keySet()) {
            if (!realPositions.contains(pos)) {
                fake++;
            }
        }

        session.record(real, leaked, leakedExposed, leakedBuried, wrongType, fake, tileLeak,
                tileReal, tileExposed, tileBuried, buriedTypes);
        if (session.isFinished()) {
            sessions.remove(playerId);
            session.report(player);
            plugin.getLogger().info(session.summary(player.getName()));
        }
    }

    /** Reports whether the watched positions leak on either channel. */
    private void reportWatch(UUID playerId, Watch watch, Column column) {
        BaseChunk[] sections = column.getChunks();
        Set<Long> tiles = scanTileEntities(column);
        java.util.List<String> lines = new java.util.ArrayList<>();
        java.util.List<String> plain = new java.util.ArrayList<>();

        for (WatchPos pos : watch.positions()) {
            int localX = pos.x() & 0xF;
            int localZ = pos.z() & 0xF;
            String seen = "(no section)";
            int index = (pos.y() - watch.minY()) >> 4;
            if (index >= 0 && index < sections.length && sections[index] != null) {
                WrappedBlockState state = sections[index].get(localX, pos.y() & 0xF, localZ);
                if (state != null) {
                    seen = stripNamespace(state.getType().getName());
                }
            }
            boolean blockLeak = pos.expected().equals(seen);
            boolean tileLeak = tiles.contains(key(localX, pos.y(), localZ));

            plain.add(String.format(Locale.ROOT, "%s [%d,%d,%d] block=%s(%s) block-entity=%s",
                    pos.label(), pos.x(), pos.y(), pos.z(),
                    blockLeak ? "LEAKED" : "hidden", seen,
                    tileLeak ? "LEAKED" : "none"));
            lines.add("§7" + pos.label() + ": block "
                    + (blockLeak ? "§cLEAKED" : "§ahidden")
                    + " §7(in packet: §f" + seen + "§7)"
                    + (tileLeak ? "  §7block-entity §cLEAKED" : ""));
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(playerId);
            String who = player != null ? player.getName() : playerId.toString().substring(0, 8);
            plugin.getLogger().info("hidden base test [" + who + "] | " + String.join(" | ", plain));
            if (player == null) {
                return;
            }
            player.sendMessage("§8§m                                        ");
            player.sendMessage("§b§lHidden base test §7(same shell, two block types)");
            lines.forEach(player::sendMessage);
            player.sendMessage("§8§m                                        ");
        });
    }

    /** A block is exposed when any of its six neighbours does not block sight. */
    private static boolean isExposed(World world, int x, int y, int z) {
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : offsets) {
            int ny = y + o[1];
            if (ny < minY || ny > maxY) {
                continue;
            }
            if (!world.getBlockAt(x + o[0], ny, z + o[2]).getType().isOccluding()) {
                return true;
            }
        }
        return false;
    }

    private static String stripNamespace(String name) {
        int colon = name.indexOf(':');
        String bare = colon >= 0 ? name.substring(colon + 1) : name;
        return bare.toLowerCase(Locale.ROOT);
    }

    /** Chunk-local (x,z) in 0..15; y is a world coordinate. */
    private static long key(int x, int y, int z) {
        return ((long) (x & 0xF) << 40) | ((long) (z & 0xF) << 36) | ((y + 2048) & 0xFFFFFFFFL);
    }

    private static final class Session {
        private final int minY;
        private int remaining;
        private int chunks;
        private int realTotal;
        private int leakedTotal;
        private int exposedTotal;
        private int buriedTotal;
        private int wrongTypeTotal;
        private int fakeTotal;
        private int tileLeakTotal;
        private int tileRealTotal;
        private int tileExposedTotal;
        private int tileBuriedTotal;
        private final Map<String, Integer> buriedByType = new HashMap<>();

        Session(int budget, int minY) {
            this.remaining = budget;
            this.minY = minY;
        }

        synchronized boolean claimChunk() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }

        synchronized void record(int real, int leaked, int exposed, int buried, int wrong,
                                 int fake, int tileLeak, int tileReal, int tileExposed,
                                 int tileBuried, Map<String, Integer> buriedTypes) {
            tileRealTotal += tileReal;
            tileExposedTotal += tileExposed;
            tileBuriedTotal += tileBuried;
            chunks++;
            realTotal += real;
            leakedTotal += leaked;
            exposedTotal += exposed;
            buriedTotal += buried;
            wrongTypeTotal += wrong;
            fakeTotal += fake;
            tileLeakTotal += tileLeak;
            buriedTypes.forEach((k, v) -> buriedByType.merge(k, v, Integer::sum));
        }

        synchronized boolean isFinished() {
            return remaining <= 0;
        }

        private String topBuriedTypes() {
            return buriedByType.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(8)
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("none");
        }

        synchronized String summary(String playerName) {
            String verdict;
            if (realTotal == 0) {
                verdict = "no sensitive blocks";
            } else if (leakedTotal == 0) {
                verdict = "NO TYPE-MATCHED LEAK";
            } else {
                verdict = String.format(Locale.ROOT, "leak %.1f%%",
                        100.0 * leakedTotal / realTotal);
            }
            int seen = fakeTotal + wrongTypeTotal + leakedTotal;
            double signal = seen == 0 ? 0 : 100.0 * leakedTotal / seen;
            return String.format(Locale.ROOT,
                    "xray audit [%s] %d chunks: real=%d TYPE-MATCHED=%d (exposed=%d buried=%d) "
                            + "wrong-type=%d fake=%d | block-entity: real=%d leaked=%d "
                            + "(exposed=%d BURIED=%d) -> %s | signal/noise=%.2f%% | "
                            + "buried by type: %s",
                    playerName, chunks, realTotal, leakedTotal, exposedTotal, buriedTotal,
                    wrongTypeTotal, fakeTotal, tileRealTotal, tileLeakTotal, tileExposedTotal,
                    tileBuriedTotal, verdict, signal, topBuriedTypes());
        }

        synchronized void report(Player player) {
            player.sendMessage("§8§m                                        ");
            player.sendMessage("§b§lX-ray leak audit §7(" + chunks + " chunks)");
            player.sendMessage("§7Sensitive blocks in world:   §f" + realTotal);
            player.sendMessage("§7Type-matched leak:           "
                    + (leakedTotal == 0 ? "§a0" : "§c" + leakedTotal));
            player.sendMessage("§7  · exposed (unavoidable):   §f" + exposedTotal);
            player.sendMessage("§7  · buried:                  §f" + buriedTotal);
            player.sendMessage("§7Wrong type (obfuscated):     §f" + wrongTypeTotal);
            player.sendMessage("§7Fake (noise):                §f" + fakeTotal);
            player.sendMessage("§7Block entities (chest/spawner): §f" + tileRealTotal
                    + "§7, leaked: §f" + tileLeakTotal);
            player.sendMessage("§7  · exposed (unavoidable):   §f" + tileExposedTotal);
            player.sendMessage("§7  · BURIED (hidden base):    "
                    + (tileBuriedTotal == 0 ? "§a0" : "§c" + tileBuriedTotal));

            int seen = fakeTotal + wrongTypeTotal + leakedTotal;
            if (seen > 0) {
                player.sendMessage(String.format(Locale.ROOT,
                        "§f%.2f%%§7 of the ore an X-ray sees is real",
                        100.0 * leakedTotal / seen));
            }
            player.sendMessage("§8§m                                        ");
        }
    }
}
