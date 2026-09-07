package lab.aclab.shield;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates fake buried chest positions.
 *
 * <p>Hiding says "you will not find it". Decoys say "what you did find is a
 * lie". Together they make a base finder's data unusable.
 *
 * <p>A decoy is only ever visible to a cheat: a chest sealed in stone has no
 * line of sight to a legitimate player, while a cheat reads every loaded chunk
 * at once. That asymmetry is what the defence rests on - legitimate play is
 * local, cheating is global.
 *
 * <p>Positions are derived deterministically from chunk coordinates. Random ones
 * would move every time a chunk is resent, which both flickers and openly
 * signals that the server is fabricating data.
 */
public final class DecoyService {

    private final int perChunk;
    private final long seed;

    /**
     * Registry id of the chest block entity.
     *
     * <p>Learned from live packets rather than hardcoded: this id changes between
     * versions and a wrong value would quietly produce corrupt packets. No decoy
     * is planted until it is known.
     */
    private final AtomicInteger chestTypeId = new AtomicInteger(-1);

    /**
     * How often a chunk carries decoys.
     *
     * <p>Every chunk is unnecessary: sparse decoys poison a cheat's data just as
     * well. Planting in every chunk instead means re-serialising every packet,
     * and that is where the real cost sits.
     */
    private final int chunkInterval;

    public DecoyService(int perChunk, long seed, int configuredChestType, int chunkInterval) {
        this.perChunk = perChunk;
        this.seed = seed;
        this.chunkInterval = Math.max(1, chunkInterval);
        if (configuredChestType >= 0) {
            chestTypeId.set(configuredChestType);
        }
    }

    public boolean enabled() {
        return perChunk > 0;
    }

    public boolean ready() {
        return chestTypeId.get() >= 0;
    }

    public int chestTypeId() {
        return chestTypeId.get();
    }

    /** Learns the id when a real chest block entity is seen. */
    public void learnChestType(int typeId) {
        chestTypeId.compareAndSet(-1, typeId);
    }

    /**
     * Candidate decoy positions for a chunk (chunk-local x, world y, chunk-local z).
     *
     * <p>Candidates only: block data is not consulted here. Whoever holds the
     * packet verifies whether a position is genuinely buried.
     */
    /** Does this chunk carry decoys? Deterministic, so identical on every resend. */
    public boolean carriesDecoys(int chunkX, int chunkZ) {
        if (!enabled()) {
            return false;
        }
        long h = seed ^ (chunkX * 0x9E3779B97F4A7C15L) ^ (chunkZ * 0xC2B2AE3D27D4EB4FL);
        h ^= (h >>> 33);
        return Math.floorMod(h, chunkInterval) == 0;
    }

    public List<int[]> candidates(int chunkX, int chunkZ, int minY, int maxY) {
        if (!carriesDecoys(chunkX, chunkZ)) {
            return List.of();
        }
        Random random = new Random(seed
                + chunkX * 341873128712L
                + chunkZ * 132897987541L);

        List<int[]> out = new ArrayList<>();
        // Several candidates per decoy; the first valid one is used.
        for (int i = 0; i < perChunk * 6; i++) {
            int lx = 2 + random.nextInt(12);
            int lz = 2 + random.nextInt(12);
            int y = minY + 8 + random.nextInt(Math.max(1, maxY - minY - 8));
            out.add(new int[]{lx, y, lz});
        }
        return out;
    }

    public int perChunk() {
        return perChunk;
    }
}
