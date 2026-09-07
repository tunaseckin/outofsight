package lab.aclab.reach;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PositionHistoryTest {

    /** x konumunda duran, 0.6 genisliginde standart bir oyuncu kutusu ornegi. */
    private static PositionHistory.Sample at(long t, double x) {
        return new PositionHistory.Sample(t, x - 0.3, 0.0, -0.3, x + 0.3, 1.8, 0.3);
    }

    @Test
    @DisplayName("aralikta ornek yoksa NaN doner - veri yokken suclama yapilmaz")
    void emptyWindowYieldsNaN() {
        PositionHistory h = new PositionHistory(20);
        h.add(at(1000, 0.0));

        double d = h.minDistanceWithin(5000, 6000, 0, 1, 0);
        assertTrue(Double.isNaN(d), "veri olmayan aralikta ihlal uretilmemeli");
    }

    @Test
    @DisplayName("geri sarma araligindaki en yakin an secilir")
    void rewindPicksClosestSampleInWindow() {
        PositionHistory h = new PositionHistory(20);
        // Kurban saldirandan uzaklasiyor: 1.0 -> 5.0
        h.add(at(1000, 1.0));
        h.add(at(1050, 3.0));
        h.add(at(1100, 5.0));

        // Saldiran (0,1,0)'da. Guncel konuma (5.0) gore mesafe ~4.7 -> ihlal gibi gorunur.
        double latest = h.minDistanceWithin(1100, 1100, 0, 1, 0);
        assertEquals(4.7, latest, 1e-9);

        // Ama 100 ms geri sarilinca kurbanin 0.7 blok mesafede oldugu an bulunur.
        double rewound = h.minDistanceWithin(1000, 1100, 0, 1, 0);
        assertEquals(0.7, rewound, 1e-9);
        assertTrue(rewound < 3.0, "geri sarma yuksek pingli mesru vurusu kurtarmali");
    }

    @Test
    @DisplayName("halka tamponu kapasiteyi asinca en eskiyi dusurur")
    void ringBufferEvictsOldest() {
        PositionHistory h = new PositionHistory(3);
        h.add(at(1000, 0.0));
        h.add(at(1001, 0.0));
        h.add(at(1002, 0.0));
        h.add(at(1003, 0.0));

        assertEquals(3, h.size());
        assertTrue(Double.isNaN(h.minDistanceWithin(1000, 1000, 0, 1, 0)),
                "en eski ornek dusmus olmali");
        assertFalse(Double.isNaN(h.minDistanceWithin(1003, 1003, 0, 1, 0)));
    }
}
