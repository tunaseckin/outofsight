package lab.aclab.shield;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Oyuncu yaklastikca tuzaklari sessizce gercek bloga cevirir.
 *
 * <p>Tuzak yalnizca hileciye gorunmelidir. Tasin icine gomulu oldugu icin mesru
 * oyuncu onu zaten goremez - tek risk, rastgele kazarken tam oraya denk gelip
 * hayalet bir sandik gormesidir. Chunk paketleri 100+ blok mesafeden gonderilir,
 * duzeltme ise bunun cok icinde calisir; aradaki pay kimsenin kazacak kadar
 * yaklasmasina firsat vermez.
 *
 * <p>Duzeltme fikirsizdir: tuzak konmus olsun olmasin, aday konumlarin gercek
 * blogu gonderilir. Zaten dogru olan bir blogu tekrar gondermek zararsizdir,
 * bu yuzden hangi tuzagin gercekten konduguna dair defter tutmaya gerek kalmaz.
 */
public final class DecoyCorrector {

    private final Plugin plugin;
    private final DecoyService decoys;
    private final int radiusChunks;

    /** Oyuncu basina duzeltilmis chunk'lar; ayni chunk tekrar tekrar gonderilmesin. */
    private final Map<UUID, Set<Long>> corrected = new ConcurrentHashMap<>();

    /** Uzun oturumlarda sinirsiz buyumesin diye ust sinir. */
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
