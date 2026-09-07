package io.github.tunaseckin.outofsight.reach;

/**
 * Ring buffer of an entity's recent bounding boxes.
 *
 * <p>An attacker sees their victim as it was one latency ago. Validating against
 * the server's current position flags legitimate high-ping players, so the
 * victim's history is kept and rewound when the attack packet arrives.
 *
 * <p>Written on the main thread, read on a network thread, hence the
 * synchronisation.
 */
public final class PositionHistory {

    /** One moment's bounding box. */
    public record Sample(long timeMs,
                         double minX, double minY, double minZ,
                         double maxX, double maxY, double maxZ) {
    }

    private final Sample[] ring;
    private int next;
    private int size;

    public PositionHistory(int capacity) {
        this.ring = new Sample[capacity];
    }

    public synchronized void add(Sample s) {
        ring[next] = s;
        next = (next + 1) % ring.length;
        if (size < ring.length) {
            size++;
        }
    }

    public synchronized void clear() {
        next = 0;
        size = 0;
        java.util.Arrays.fill(ring, null);
    }

    public synchronized int size() {
        return size;
    }

    /**
     * Closest approach to the point among samples in [fromMs, toMs].
     *
     * <p>Taking the minimum is deliberate: if there is a single moment in the
     * window where the victim was reachable, the hit was legitimate. Reading
     * doubt in the player's favour is the most important choice an anticheat
     * makes.
     *
     * @return the shortest distance, or {@link Double#NaN} if the window is empty
     */
    public synchronized double minDistanceWithin(long fromMs, long toMs,
                                                 double px, double py, double pz) {
        double best = Double.NaN;
        for (Sample s : ring) {
            if (s == null || s.timeMs() < fromMs || s.timeMs() > toMs) {
                continue;
            }
            double d = ReachMath.distanceToBox(px, py, pz,
                    s.minX(), s.minY(), s.minZ(), s.maxX(), s.maxY(), s.maxZ());
            if (Double.isNaN(best) || d < best) {
                best = d;
            }
        }
        return best;
    }

    /**
     * Samples inside the given window.
     *
     * <p>The attacker has to be rewound as well as the victim: both are moving,
     * and a legitimate hit is one where <em>some</em> pair of moments in their
     * histories could reach each other.
     */
    public synchronized java.util.List<Sample> samplesWithin(long fromMs, long toMs) {
        java.util.List<Sample> out = new java.util.ArrayList<>();
        for (Sample s : ring) {
            if (s != null && s.timeMs() >= fromMs && s.timeMs() <= toMs) {
                out.add(s);
            }
        }
        return out;
    }
}
