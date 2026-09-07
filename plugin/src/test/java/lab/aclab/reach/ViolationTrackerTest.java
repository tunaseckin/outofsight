package lab.aclab.reach;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ViolationTrackerTest {

    @Test
    @DisplayName("violations accumulate")
    void violationsAccumulate() {
        ViolationTracker v = new ViolationTracker(1.0);
        assertEquals(1.0, v.add(1.0, 1000), 1e-9);
        assertEquals(2.0, v.add(1.0, 1000), 1e-9);
    }

    @Test
    @DisplayName("clean time decays the level")
    void levelDecaysOverTime() {
        ViolationTracker v = new ViolationTracker(1.0); // 1.0 per second
        v.add(5.0, 1000);
        assertEquals(2.0, v.current(4000), 1e-9); // after 3 seconds: 5 - 3 = 2
    }

    @Test
    @DisplayName("decay floors at zero")
    void decayFloorsAtZero() {
        ViolationTracker v = new ViolationTracker(1.0);
        v.add(2.0, 1000);
        assertEquals(0.0, v.current(60_000), 1e-9);
    }
}
