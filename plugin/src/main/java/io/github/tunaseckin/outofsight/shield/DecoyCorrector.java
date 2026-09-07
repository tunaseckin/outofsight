package io.github.tunaseckin.outofsight.shield;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

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

    /** Chunks already corrected per player, so none is sent repeatedly. */
    private final Map<UUID, Set<Long>> corrected = new ConcurrentHashMap<>();

    /** Upper bound so this cannot grow without limit over a long session. */
    private static final int MAX_TRACKED = 4096;

    public DecoyCorrector(Plugin plugin, DecoyService decoys, int radiusChunks) {
        this.plugin = plugin;
        this.decoys = decoys;
        this.radiusChunks = radiusChunks;
    }

    public void forget(Player player) {
        corrected.remove(player.getUniqueId());
    }

    public void run() {
        if (!decoys.enabled()) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Set<Long> seen = corrected.computeIfAbsent(player.getUniqueId(),
                    k -> ConcurrentHashMap.newKeySet());
            if (seen.size() > MAX_TRACKED) {
                seen.clear();
            }
            World world = player.getWorld();
            int pcx = player.getLocation().getBlockX() >> 4;
            int pcz = player.getLocation().getBlockZ() >> 4;

            for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
                for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    if (!seen.add(HiddenIndex.chunkKey(cx, cz)) || !world.isChunkLoaded(cx, cz)) {
                        continue;
                    }
                    correctChunk(player, world, cx, cz);
                }
            }
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
