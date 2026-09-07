package lab.aclab.shield;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Index of block entity positions to hide.
 *
 * <p>Why enclosure is decided here rather than from the packet: a packet carries
 * a single chunk column, so a block on a chunk border has neighbours that are
 * not available - about a quarter of positions would go undecided. A decision
 * read from the packet is also only valid at send time; when a player breaks the
 * wall there is nobody left to announce that the chest became visible.
 *
 * <p>The index is written on the main thread (where the world can be read) and
 * read on network threads, hence the concurrent structures.
 */
public final class HiddenIndex {

    private final Map<Long, Set<Long>> byChunk = new ConcurrentHashMap<>();

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** Packs absolute world coordinates into a single key. */
    public static long posKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public void hide(int x, int y, int z) {
        byChunk.computeIfAbsent(chunkKey(x >> 4, z >> 4), k -> ConcurrentHashMap.newKeySet())
                .add(posKey(x, y, z));
    }

    /** Drops a position from the index; returns {@code true} if it was present. */
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

    /** Decodes coordinates from a chunk key. */
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

    /** Snapshot for the periodic sweep; the index may change while iterating. */
    public Map<Long, Set<Long>> snapshot() {
        return Map.copyOf(byChunk);
    }

    public int size() {
        return byChunk.values().stream().mapToInt(Set::size).sum();
    }
}
