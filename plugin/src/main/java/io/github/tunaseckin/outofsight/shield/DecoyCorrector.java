package io.github.tunaseckin.outofsight.shield;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quietly reverts decoys to the real block as a player approaches.
 *
 * <p>A decoy should only ever be visible to a cheat. Sealed in stone it already
 * is - the one risk is a player digging into one by chance and seeing a phantom
 * chest. Chunk packets are sent from 100+ blocks away and correction runs well
 * inside that, leaving no window to reach one.
 *
 * <p>Correction is deliberately dumb: the real block is sent for every candidate
 * position whether or not a decoy was planted there. Resending a block that is
 * already correct is harmless, which removes any need to track what was planted.
 */
public final class DecoyCorrector {

    private final Plugin plugin;
    private final DecoyService decoys;
    private final int radiusChunks;

    /**
     * Chunks inside each player's radius that were corrected on the last run.
     *
     * <p>Only chunks still in range are kept. A chunk the player walks away
     * from is resent with its decoys when they come back, so it has to be
     * corrected again; remembering it forever left those decoys standing.
     */
    private final Map<UUID, Set<Long>> corrected = new ConcurrentHashMap<>();

    /** The world each entry in {@link #corrected} belongs to. */
    private final Map<UUID, UUID> correctedWorld = new ConcurrentHashMap<>();

    public DecoyCorrector(Plugin plugin, DecoyService decoys, int radiusChunks) {
        this.plugin = plugin;
        this.decoys = decoys;
        this.radiusChunks = radiusChunks;
    }

    public void forget(Player player) {
        corrected.remove(player.getUniqueId());
        correctedWorld.remove(player.getUniqueId());
    }

    public void run() {
        if (!decoys.enabled()) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            World world = player.getWorld();
            Set<Long> previous = corrected.getOrDefault(id, Set.of());
            if (!world.getUID().equals(correctedWorld.put(id, world.getUID()))) {
                previous = Set.of(); // New world, nothing there is corrected yet.
            }
            Set<Long> current = new HashSet<>();
            int pcx = player.getLocation().getBlockX() >> 4;
            int pcz = player.getLocation().getBlockZ() >> 4;

            for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
                for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    long key = HiddenIndex.chunkKey(cx, cz);
                    if (previous.contains(key)) {
                        current.add(key);
                    } else if (world.isChunkLoaded(cx, cz)) {
                        // Only marked once actually corrected, so a chunk that
                        // was not loaded yet is retried on the next run.
                        correctChunk(player, world, cx, cz);
                        current.add(key);
                    }
                }
            }
            corrected.put(id, current);
        }
    }

    private void correctChunk(Player player, World world, int chunkX, int chunkZ) {
        for (int[] candidate : decoys.candidates(chunkX, chunkZ, world.getMinHeight(), 60)) {
            int x = (chunkX << 4) + candidate[0];
            int y = candidate[1];
            int z = (chunkZ << 4) + candidate[2];
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                continue;
            }
            var block = world.getBlockAt(x, y, z);
            player.sendBlockChange(block.getLocation(), block.getBlockData());
        }
    }
}
