package lab.aclab.shield;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gizlenecek block entity konumlarinin dizini.
 *
 * <p>Gomululuk karari neden pakette degil burada veriliyor: paket yalnizca tek
 * bir chunk sutunu tasir, dolayisiyla chunk kenarindaki bir blogun komsulari
 * elde olmaz - konumlarin dortte biri karar disi kalir. Ayrica paketten okunan
 * bir karar sadece paket gonderilirken gecerlidir; oyuncu duvari kirdiginda
 * sandigin artik gorunur oldugunu haber verecek kimse olmaz.
 *
 * <p>Dizin ana is parcaciginda yazilir (dunyayi orada okuyabiliriz) ve ag is
 * parcaciginda okunur, bu yuzden eszamanli yapilar kullanilir.
 */
public final class HiddenIndex {

    private final Map<Long, Set<Long>> byChunk = new ConcurrentHashMap<>();

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** Mutlak dunya koordinatlarini tek bir anahtara paketler. */
    public static long posKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public void hide(int x, int y, int z) {
        byChunk.computeIfAbsent(chunkKey(x >> 4, z >> 4), k -> ConcurrentHashMap.newKeySet())
                .add(posKey(x, y, z));
    }

    /** Konumu dizinden dusurur; gercekten dizindeyse {@code true} doner. */
    public boolean reveal(int x, int y, int z) {
        Set<Long> set = byChunk.get(chunkKey(x >> 4, z >> 4));
        return set != null && set.remove(posKey(x, y, z));
    }

    public boolean isHidden(int x, int y, int z) {
        Set<Long> set = byChunk.get(chunkKey(x >> 4, z >> 4));
        return set != null && set.contains(posKey(x, y, z));
    }

    public boolean hasChunk(int chunkX, int chunkZ) {
        Set<Long> set = byChunk.get(chunkKey(chunkX, chunkZ));
        return set != null && !set.isEmpty();
    }

    public void clearChunk(int chunkX, int chunkZ) {
        byChunk.remove(chunkKey(chunkX, chunkZ));
    }

    /** Chunk anahtarindan koordinat cozer. */
    public static int chunkXOf(long key) {
        return (int) (key >> 32);
    }

    public static int chunkZOf(long key) {
        return (int) key;
    }

    public static int posXOf(long key) {
        return (int) (key >> 38);
    }

    public static int posYOf(long key) {
        return (int) (key << 52 >> 52);
    }

    public static int posZOf(long key) {
        return (int) (key << 26 >> 38);
    }

    /** Sureli tarama icin anlik goruntu; uzerinde gezerken dizin degisebilir. */
    public Map<Long, Set<Long>> snapshot() {
        return Map.copyOf(byChunk);
    }

    public int size() {
        return byChunk.values().stream().mapToInt(Set::size).sum();
    }
}
