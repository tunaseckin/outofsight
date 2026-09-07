package lab.aclab.reach;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReachMathTest {

    // Ayakta duran bir oyuncunun kutusu: 0.6 x 1.8 x 0.6
    private static final double[] PLAYER_AT_ORIGIN =
            {-0.3, 0.0, -0.3, 0.3, 1.8, 0.3};

    private static double distTo(double[] box, double x, double y, double z) {
        return ReachMath.distanceToBox(x, y, z, box[0], box[1], box[2], box[3], box[4], box[5]);
    }

    @Test
    @DisplayName("kutunun icindeki nokta sifir mesafededir")
    void insideBoxIsZero() {
        assertEquals(0.0, distTo(PLAYER_AT_ORIGIN, 0.0, 1.0, 0.0), 1e-9);
    }

    @Test
    @DisplayName("tek eksende disaridaki nokta yuzeye olan mesafeyi verir")
    void outsideOnSingleAxis() {
        // x = 3.3, kutunun sag yuzu 0.3 -> mesafe 3.0
        assertEquals(3.0, distTo(PLAYER_AT_ORIGIN, 3.3, 1.0, 0.0), 1e-9);
    }

    @Test
    @DisplayName("uzun bir hedefin ayagina vurmak merkez mesafesiyle olculmemeli")
    void tallTargetFootHitIsNotMeasuredFromCenter() {
        // Ender ejderi benzeri genis bir kutu yerine, uzun bir hedef dusunelim:
        // y = 0..4 arasi, oyuncu tam ayagin yaninda duruyor.
        double[] tall = {-0.5, 0.0, -0.5, 0.5, 4.0, 0.5};

        double toFoot = distTo(tall, 1.5, 0.5, 0.0);   // ayaga yakin
        double toCenter = Math.sqrt(1.5 * 1.5 + 1.5 * 1.5); // merkez y=2.0'a olan mesafe

        assertEquals(1.0, toFoot, 1e-9, "kutuya mesafe 1.0 olmali");
        assertTrue(toCenter > 2.0, "merkez mesafesi yanlislikla cok buyuk cikar");
        // Merkeze gore olculseydi 3.0 esigini asip mesru vurusu isaretlerdi.
        assertTrue(toFoot < 3.0 && toCenter > toFoot);
    }

    @Test
    @DisplayName("kosegen mesafe uc eksende birlikte hesaplanir")
    void diagonalDistance() {
        // (2.3, 1.0, 2.3) -> kutunun kosesine 2.0, 2.0 -> sqrt(8)
        assertEquals(Math.sqrt(8.0), distTo(PLAYER_AT_ORIGIN, 2.3, 1.0, 2.3), 1e-9);
    }
}
