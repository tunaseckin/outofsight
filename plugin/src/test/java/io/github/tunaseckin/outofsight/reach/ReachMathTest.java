package io.github.tunaseckin.outofsight.reach;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReachMathTest {

    // A standing player's box: 0.6 x 1.8 x 0.6
    private static final double[] PLAYER_AT_ORIGIN =
            {-0.3, 0.0, -0.3, 0.3, 1.8, 0.3};

    private static double distTo(double[] box, double x, double y, double z) {
        return ReachMath.distanceToBox(x, y, z, box[0], box[1], box[2], box[3], box[4], box[5]);
    }

    @Test
    @DisplayName("a point inside the box is at distance zero")
    void insideBoxIsZero() {
        assertEquals(0.0, distTo(PLAYER_AT_ORIGIN, 0.0, 1.0, 0.0), 1e-9);
    }

    @Test
    @DisplayName("a point outside on one axis measures to the face")
    void outsideOnSingleAxis() {
        // x = 3.3, the box's right face is at 0.3 -> distance 3.0
        assertEquals(3.0, distTo(PLAYER_AT_ORIGIN, 3.3, 1.0, 0.0), 1e-9);
    }

    @Test
    @DisplayName("hitting a tall target's feet must not be measured from its centre")
    void tallTargetFootHitIsNotMeasuredFromCenter() {
        // A tall target spanning y = 0..4, with the player standing by its feet.
        double[] tall = {-0.5, 0.0, -0.5, 0.5, 4.0, 0.5};

        double toFoot = distTo(tall, 1.5, 0.5, 0.0);        // near the feet
        double toCenter = Math.sqrt(1.5 * 1.5 + 1.5 * 1.5); // to the centre at y=2.0

        assertEquals(1.0, toFoot, 1e-9, "distance to the box should be 1.0");
        assertTrue(toCenter > 2.0, "the centre distance comes out wrongly large");
        // Measured from the centre this would cross 3.0 and flag a legitimate hit.
        assertTrue(toFoot < 3.0 && toCenter > toFoot);
    }

    @Test
    @DisplayName("diagonal distance combines all three axes")
    void diagonalDistance() {
        // (2.3, 1.0, 2.3) -> 2.0 and 2.0 to the corner -> sqrt(8)
        assertEquals(Math.sqrt(8.0), distTo(PLAYER_AT_ORIGIN, 2.3, 1.0, 2.3), 1e-9);
    }
}
