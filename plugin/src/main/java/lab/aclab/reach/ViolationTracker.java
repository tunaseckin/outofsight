package lab.aclab.reach;

/**
 * Violation level with decay over time.
 *
 * <p>Punishing a single violation means false positives: a packet delay, a
 * server hiccup or an edge case will eventually happen to every player. What
 * carries meaning is violations <em>accumulating</em>. Clean time brings the
 * level back down.
 */
public final class ViolationTracker {

    private final double decayPerSecond;
    private double level;
    private long lastUpdateMs;

    public ViolationTracker(double decayPerSecond) {
        this.decayPerSecond = decayPerSecond;
    }

    /** Adds a violation and returns the level after decay. */
    public double add(double amount, long nowMs) {
        decayTo(nowMs);
        level += amount;
        return level;
    }

    /** Current level without adding a violation. */
    public double current(long nowMs) {
        decayTo(nowMs);
        return level;
    }

    public void reset() {
        level = 0;
        lastUpdateMs = 0;
    }

    private void decayTo(long nowMs) {
        if (lastUpdateMs != 0 && nowMs > lastUpdateMs) {
            double elapsedSec = (nowMs - lastUpdateMs) / 1000.0;
            level = Math.max(0.0, level - elapsedSec * decayPerSecond);
        }
        lastUpdateMs = nowMs;
    }
}
