package lab.aclab.reach;

/**
 * Ihlal seviyesi ve zamanla sonumlenmesi.
 *
 * <p>Tek bir ihlalde ceza vermek yanlis pozitif demektir: bir paket gecikmesi,
 * bir sunucu takilmasi ya da bir kenar durumu her oyuncuda er ya da gec olusur.
 * Anlamli olan, ihlallerin <em>birikmesidir</em>. Temiz gecen sure ihlal
 * seviyesini geri dusurur.
 */
public final class ViolationTracker {

    private final double decayPerSecond;
    private double level;
    private long lastUpdateMs;

    public ViolationTracker(double decayPerSecond) {
        this.decayPerSecond = decayPerSecond;
    }

    /** Bir ihlal ekler ve sonumlemeden sonraki guncel seviyeyi dondurur. */
    public double add(double amount, long nowMs) {
        decayTo(nowMs);
        level += amount;
        return level;
    }

    /** Ihlal eklemeden guncel seviyeyi dondurur. */
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
