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

    /** Every protected container position, grouped by chunk. */
    private final Map<Long, Set<Long>> containers = new ConcurrentHashMap<>();

    /** The subset with no visible face. */
    private final Map<Long, Set<Long>> enclosed = new ConcurrentHashMap<>();

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

    // --- container membership ---------------------------------------------

    public void addContainer(int x, int y, int z) {
        containers.computeIfAbsent(chunkKey(x >> 4, z >> 4), k -> ConcurrentHashMap.newKeySet())
                .add(posKey(x, y, z));
    }

    public void removeContainer(int x, int y, int z) {
        long chunk = chunkKey(x >> 4, z >> 4);
        Set<Long> set = containers.get(chunk);
        if (set != null) {
            set.remove(posKey(x, y, z));
        }
        setEnclosed(x, y, z, false);
    }

    public boolean isContainer(int x, int y, int z) {
        Set<Long> set = containers.get(chunkKey(x >> 4, z >> 4));
        return set != null && set.contains(posKey(x, y, z));
    }

    public boolean hasChunk(int chunkX, int chunkZ) {
        Set<Long> set = containers.get(chunkKey(chunkX, chunkZ));
        return set != null && !set.isEmpty();
    }

    public Set<Long> containersIn(int chunkX, int chunkZ) {
        return containers.getOrDefault(chunkKey(chunkX, chunkZ), Set.of());
    }

    public void clearChunk(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        containers.remove(key);
        enclosed.remove(key);
    }

    // --- enclosure --------------------------------------------------------

    /** @return true when the flag changed */
    public boolean setEnclosed(int x, int y, int z, boolean value) {
        long chunk = chunkKey(x >> 4, z >> 4);
        long pos = posKey(x, y, z);
        if (value) {
            return enclosed.computeIfAbsent(chunk, k -> ConcurrentHashMap.newKeySet()).add(pos);
        }
        Set<Long> set = enclosed.get(chunk);
        return set != null && set.remove(pos);
    }

    public boolean isEnclosed(int x, int y, int z) {
        Set<Long> set = enclosed.get(chunkKey(x >> 4, z >> 4));
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
        return containers.values().stream().mapToInt(Set::size).sum();
    }
}
