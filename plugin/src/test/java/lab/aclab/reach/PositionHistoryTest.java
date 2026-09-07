package lab.aclab.reach;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PositionHistoryTest {

    /** A standard 0.6-wide player box standing at x. */
    private static PositionHistory.Sample at(long t, double x) {
        return new PositionHistory.Sample(t, x - 0.3, 0.0, -0.3, x + 0.3, 1.8, 0.3);
    }

    @Test
    @DisplayName("an empty window yields NaN - no accusation without data")
    void emptyWindowYieldsNaN() {
        PositionHistory h = new PositionHistory(20);
        h.add(at(1000, 0.0));

        double d = h.minDistanceWithin(5000, 6000, 0, 1, 0);
        assertTrue(Double.isNaN(d), "a window with no data must not produce a violation");
    }

    @Test
    @DisplayName("rewinding picks the closest moment in the window")
    void rewindPicksClosestSampleInWindow() {
        PositionHistory h = new PositionHistory(20);
        // The victim is moving away: 1.0 -> 5.0
        h.add(at(1000, 1.0));
        h.add(at(1050, 3.0));
        h.add(at(1100, 5.0));

        // Attacker at (0,1,0). Against the current position (5.0) it reads ~4.7 - a violation.
        double latest = h.minDistanceWithin(1100, 1100, 0, 1, 0);
        assertEquals(4.7, latest, 1e-9);

        // Rewound 100 ms, the moment where the victim was 0.7 blocks away is found.
        double rewound = h.minDistanceWithin(1000, 1100, 0, 1, 0);
        assertEquals(0.7, rewound, 1e-9);
        assertTrue(rewound < 3.0, "rewinding should save a legitimate high-ping hit");
    }

    @Test
    @DisplayName("the ring buffer evicts the oldest sample past capacity")
    void ringBufferEvictsOldest() {
        PositionHistory h = new PositionHistory(3);
        h.add(at(1000, 0.0));
        h.add(at(1001, 0.0));
        h.add(at(1002, 0.0));
        h.add(at(1003, 0.0));

        assertEquals(3, h.size());
        assertTrue(Double.isNaN(h.minDistanceWithin(1000, 1000, 0, 1, 0)),
                "the oldest sample should be gone");
        assertFalse(Double.isNaN(h.minDistanceWithin(1003, 1003, 0, 1, 0)));
    }
}
