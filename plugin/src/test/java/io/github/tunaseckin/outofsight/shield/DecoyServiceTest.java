package io.github.tunaseckin.outofsight.shield;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DecoyServiceTest {

    @Test
    @DisplayName("positions are identical on every resend, so decoys never flicker")
    void deterministic() {
        DecoyService a = new DecoyService(2, 12345L, 7, 4);
        DecoyService b = new DecoyService(2, 12345L, 7, 4);
        for (int cx = -20; cx <= 20; cx++) {
            for (int cz = -20; cz <= 20; cz++) {
                assertEquals(a.carriesDecoys(cx, cz), b.carriesDecoys(cx, cz));
                List<int[]> ca = a.candidates(cx, cz, -64, 60);
                List<int[]> cb = b.candidates(cx, cz, -64, 60);
                assertEquals(ca.size(), cb.size());
                for (int i = 0; i < ca.size(); i++) {
                    assertArrayEquals(ca.get(i), cb.get(i));
                }
            }
        }
    }

    @Test
    @DisplayName("about one chunk in four carries decoys")
    void thinnedToInterval() {
        DecoyService decoys = new DecoyService(1, 987654321L, 7, 4);
        int carrying = 0;
        int total = 0;
        for (int cx = -100; cx < 100; cx++) {
            for (int cz = -100; cz < 100; cz++) {
                total++;
                if (decoys.carriesDecoys(cx, cz)) {
                    carrying++;
                }
            }
        }
        double share = (double) carrying / total;
        assertEquals(0.25, share, 0.02, "share of chunks with decoys: " + share);
    }

    @Test
    @DisplayName("candidates stay inside the chunk, away from its border, and between floor and ceiling")
    void candidatesInBounds() {
        DecoyService decoys = new DecoyService(3, 42L, 7, 1);
        for (int minY : new int[]{-64, 0}) {
            for (int cx = -10; cx <= 10; cx++) {
                for (int[] c : decoys.candidates(cx, 3, minY, 60)) {
                    assertTrue(c[0] >= 2 && c[0] <= 13, "x " + c[0]);
                    assertTrue(c[2] >= 2 && c[2] <= 13, "z " + c[2]);
                    assertTrue(c[1] >= minY + 8 && c[1] < 60, "y " + c[1] + " for floor " + minY);
                }
            }
        }
    }

    @Test
    @DisplayName("off when zero per chunk, and nothing planted until the chest type is known")
    void disabledAndReady() {
        DecoyService off = new DecoyService(0, 1L, -1, 4);
        assertFalse(off.enabled());
        assertFalse(off.carriesDecoys(0, 0));

        DecoyService learning = new DecoyService(1, 1L, -1, 4);
        assertFalse(learning.ready());
        learning.learnChestType(9);
        learning.learnChestType(11);
        assertTrue(learning.ready());
        assertEquals(9, learning.chestTypeId(), "first learned id sticks");
    }

    @Test
    @DisplayName("planting uses only candidates whose block and six neighbours are solid")
    void plantNeedsBuriedSpot() {
        DecoyService decoys = new DecoyService(2, 7L, 7, 1);
        // Everything solid: the first two candidates are used.
        List<int[]> all = decoys.plant(4, 9, -64, (lx, y, lz) -> true);
        assertEquals(2, all.size());
        List<int[]> candidates = decoys.candidates(4, 9, -64, 60);
        assertArrayEquals(candidates.get(0), all.get(0));
        assertArrayEquals(candidates.get(1), all.get(1));

        // Air above the first candidate: it is skipped and a later one is used.
        int[] first = candidates.get(0);
        List<int[]> skipping = decoys.plant(4, 9, -64,
                (lx, y, lz) -> !(lx == first[0] && y == first[1] + 1 && lz == first[2]));
        assertEquals(2, skipping.size());
        for (int[] c : skipping) {
            assertFalse(java.util.Arrays.equals(first, c), "exposed candidate must not be planted");
        }

        // Nothing solid: no decoys at all.
        assertTrue(decoys.plant(4, 9, -64, (lx, y, lz) -> false).isEmpty());
    }

    @Test
    @DisplayName("planted spots are remembered per world")
    void plantedPerWorld() {
        DecoyService decoys = new DecoyService(1, 7L, 7, 1);
        UUID overworld = UUID.randomUUID();
        UUID nether = UUID.randomUUID();
        long pos = HiddenIndex.posKey(100, 12, -40);
        assertFalse(decoys.wasPlanted(overworld, pos));
        decoys.recordPlanted(overworld, pos);
        assertTrue(decoys.wasPlanted(overworld, pos));
        assertFalse(decoys.wasPlanted(nether, pos));
        assertFalse(decoys.wasPlanted(overworld, HiddenIndex.posKey(100, 13, -40)));
    }
}
