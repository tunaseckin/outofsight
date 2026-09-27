package io.github.tunaseckin.outofsight.shield;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the protected containers are, and which of them a given player may see.
 *
 * <p>The rule that decides hiding is distance, not enclosure. Base finding is a
 * long range attack by definition: the cheat loads chunks out to render distance
 * and reads every container in them at once. A legitimate player is standing next
 * to the chest they care about. Vanilla clients do not draw block entities much
 * past this range either, so withholding the distant ones costs an honest player
 * nothing and costs a scanner everything.
 *
 * <p>Enclosure still matters as a second reason to hide, because a chest walled
 * into stone is invisible to a player standing beside it. Hiding on enclosure
 * alone was the earlier design and it protected almost nothing: a chest anyone
 * can actually open has air above it, so it was never enclosed.
 *
 * <p>Written on the main thread, read on network threads.
 */
public final class HiddenIndex {

    /**
     * Every protected container position, grouped by world and then by chunk.
     *
     * <p>Keyed by world because chunk and block coordinates repeat across
     * dimensions: without it, the Nether unloading chunk 0,0 would wipe the
     * Overworld's containers in chunk 0,0 and leave them unprotected.
     */
    private final Map<UUID, Map<Long, Set<Long>>> containers = new ConcurrentHashMap<>();

    /** The subset with no visible face, keyed the same way. */
    private final Map<UUID, Map<Long, Set<Long>>> enclosed = new ConcurrentHashMap<>();

    /**
     * What has already been sent to each player.
     *
     * <p>This is what the network thread checks. Whether a player may see a
     * container needs the world, which only the main thread can read, so the
     * decision is made there and recorded here. The packet then answers a
     * question it can answer on its own: has this player been given this
     * container yet?
     */
    private final Map<UUID, Set<Long>> delivered = new ConcurrentHashMap<>();

    /** Bumped whenever a container is added or dropped, so callers can skip unchanged state. */
    private final java.util.concurrent.atomic.AtomicLong version =
            new java.util.concurrent.atomic.AtomicLong();

    public long version() {
        return version.get();
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** Packs absolute world coordinates into a single key. */
    public static long posKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

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

    private static Set<Long> chunkSet(Map<UUID, Map<Long, Set<Long>>> map, UUID world,
                                      int chunkX, int chunkZ) {
        Map<Long, Set<Long>> chunks = map.get(world);
        return chunks == null ? null : chunks.get(chunkKey(chunkX, chunkZ));
    }

    private static Set<Long> chunkSetOrCreate(Map<UUID, Map<Long, Set<Long>>> map, UUID world,
                                              int chunkX, int chunkZ) {
        return map.computeIfAbsent(world, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkKey(chunkX, chunkZ), k -> ConcurrentHashMap.newKeySet());
    }

    // --- container membership ---------------------------------------------

    public void addContainer(UUID world, int x, int y, int z) {
        if (chunkSetOrCreate(containers, world, x >> 4, z >> 4).add(posKey(x, y, z))) {
            version.incrementAndGet();
        }
    }

    public void removeContainer(UUID world, int x, int y, int z) {
        Set<Long> set = chunkSet(containers, world, x >> 4, z >> 4);
        if (set != null && set.remove(posKey(x, y, z))) {
            version.incrementAndGet();
        }
        setEnclosed(world, x, y, z, false);
    }

    public boolean isContainer(UUID world, int x, int y, int z) {
        Set<Long> set = chunkSet(containers, world, x >> 4, z >> 4);
        return set != null && set.contains(posKey(x, y, z));
    }

    public boolean hasChunk(UUID world, int chunkX, int chunkZ) {
        Set<Long> set = chunkSet(containers, world, chunkX, chunkZ);
        return set != null && !set.isEmpty();
    }

    public Set<Long> containersIn(UUID world, int chunkX, int chunkZ) {
        Set<Long> set = chunkSet(containers, world, chunkX, chunkZ);
        return set != null ? set : Set.of();
    }

    public void clearChunk(UUID world, int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        Map<Long, Set<Long>> worldContainers = containers.get(world);
        if (worldContainers != null && worldContainers.remove(key) != null) {
            version.incrementAndGet();
        }
        Map<Long, Set<Long>> worldEnclosed = enclosed.get(world);
        if (worldEnclosed != null) {
            worldEnclosed.remove(key);
        }
    }

    // --- enclosure --------------------------------------------------------

    /** @return true when the flag changed */
    public boolean setEnclosed(UUID world, int x, int y, int z, boolean value) {
        long pos = posKey(x, y, z);
        if (value) {
            return chunkSetOrCreate(enclosed, world, x >> 4, z >> 4).add(pos);
        }
        Set<Long> set = chunkSet(enclosed, world, x >> 4, z >> 4);
        return set != null && set.remove(pos);
    }

    public boolean isEnclosed(UUID world, int x, int y, int z) {
        Set<Long> set = chunkSet(enclosed, world, x >> 4, z >> 4);
        return set != null && set.contains(posKey(x, y, z));
    }

    // --- delivery bookkeeping ---------------------------------------------

    public boolean isDelivered(UUID player, int x, int y, int z) {
        Set<Long> set = delivered.get(player);
        return set != null && set.contains(posKey(x, y, z));
    }

    /** @return true when this is the first delivery of that position */
    public boolean markDelivered(UUID player, long pos) {
        return delivered.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(pos);
    }

    /**
     * Drops everything not in {@code keep}, so a container left behind is hidden
     * again the next time its chunk is sent. The client keeps what it already
     * has, so this changes nothing until then, which is the point.
     */
    public void retainDelivered(UUID player, Set<Long> keep) {
        Set<Long> set = delivered.get(player);
        if (set != null) {
            set.retainAll(keep);
        }
    }

    /**
     * Drops everything a player was given. Called on a world change: positions
     * carry no world, so a container delivered in one dimension must not count
     * as delivered for the same coordinates in another.
     */
    public void clearDelivered(UUID player) {
        delivered.remove(player);
    }

    public void forgetPlayer(UUID player) {
        delivered.remove(player);
        shielded.remove(player);
    }

    // --- test mode --------------------------------------------------------

    /**
     * Players the shield currently applies to while test mode is on.
     *
     * <p>Permissions can only be read on the main thread, so the answer is cached
     * here for the network thread. Without test mode an admin has to switch the
     * shield on for everyone at once to find out whether it works, which is not
     * something anyone should do to a server with players on it.
     */
    private final Set<UUID> shielded = ConcurrentHashMap.newKeySet();

    public void setShielded(UUID player, boolean value) {
        if (value) {
            shielded.add(player);
        } else {
            shielded.remove(player);
        }
    }

    public boolean isShielded(UUID player) {
        return shielded.contains(player);
    }

    public int size() {
        return containers.values().stream()
                .flatMap(chunks -> chunks.values().stream())
                .mapToInt(Set::size).sum();
    }
}
