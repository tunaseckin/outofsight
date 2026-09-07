package lab.aclab.reach;

/**
 * Bir varligin son konumlarinin halka tamponu.
 *
 * <p>Saldiran oyuncu kurbani, kendi gecikmesi kadar <em>gecmiste</em> gorur.
 * Sunucudaki guncel konuma gore dogrulama yapmak, yuksek pingli mesru
 * oyunculari isaretler. Bu yuzden kurbanin gecmisi saklanir ve saldiri
 * paketi geldiginde ilgili zaman araligi geri sarilir.
 *
 * <p>Ana is parcaciginda yazilir, ag is parcaciginda okunur; bu yuzden
 * erisim senkronizedir.
 */
public final class PositionHistory {

    /** Tek bir anin kutusu. */
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
     * [fromMs, toMs] araligindaki ornekler icinde noktaya en yakin olani dondurur.
     *
     * <p>En kucugu almak kasitlidir: aralikta kurbanin ulasilabilir oldugu tek bir
     * an bile varsa vurus mesrudur. Supheyi oyuncunun lehine yorumlamak, bir
     * anticheat'in yapabilecegi en onemli tercihtir.
     *
     * @return en kisa mesafe; aralikta hic ornek yoksa {@link Double#NaN}
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
     * Verilen zaman araligindaki ornekleri dondurur.
     *
     * <p>Sadece kurbani degil saldirani da geri sarmak gerekir: ikisi de
     * hareket halindedir ve mesru vurus, ikisinin gecmisindeki <em>herhangi</em>
     * bir an ciftinin birbirine ulasabildigi durumdur.
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
