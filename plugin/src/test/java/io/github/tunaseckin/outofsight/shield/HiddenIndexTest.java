package io.github.tunaseckin.outofsight.shield;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HiddenIndexTest {

    @Test
    @DisplayName("position keys round-trip across the whole world border and height range")
    void posKeyRoundTrip() {
        int[] xs = {-30_000_000, -1, 0, 15, 16, 29_999_999};
        int[] ys = {-64, -1, 0, 255, 319, 2031, -2032};
        for (int x : xs) {
            for (int y : ys) {
                for (int z : xs) {
                    long key = HiddenIndex.posKey(x, y, z);
                    assertEquals(x, HiddenIndex.posXOf(key), "x");
                    assertEquals(y, HiddenIndex.posYOf(key), "y");
                    assertEquals(z, HiddenIndex.posZOf(key), "z");
                }
            }
        }
    }

    @Test
    @DisplayName("chunk keys round-trip for negative coordinates")
    void chunkKeyRoundTrip() {
        for (int cx : new int[]{-1_875_000, -1, 0, 1, 1_874_999}) {
            for (int cz : new int[]{-1_875_000, -1, 0, 1, 1_874_999}) {
                long key = HiddenIndex.chunkKey(cx, cz);
                assertEquals(cx, HiddenIndex.chunkXOf(key));
                assertEquals(cz, HiddenIndex.chunkZOf(key));
            }
        }
    }

    @Test
    @DisplayName("unloading a chunk in one world leaves the same coordinates in another alone")
    void worldsAreSeparate() {
        HiddenIndex index = new HiddenIndex();
        UUID overworld = UUID.randomUUID();
        UUID nether = UUID.randomUUID();
        index.addContainer(overworld, 5, -30, 7);
        index.addContainer(nether, 5, -30, 7);

        index.clearChunk(nether, 0, 0);

        assertTrue(index.isContainer(overworld, 5, -30, 7));
        assertFalse(index.isContainer(nether, 5, -30, 7));
        assertTrue(index.hasChunk(overworld, 0, 0));
        assertFalse(index.hasChunk(nether, 0, 0));
    }

    @Test
    @DisplayName("enclosure is tracked per world and cleared with the container")
    void enclosurePerWorld() {
        HiddenIndex index = new HiddenIndex();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        index.addContainer(a, -17, 40, -33);
        assertTrue(index.setEnclosed(a, -17, 40, -33, true));
        assertFalse(index.setEnclosed(a, -17, 40, -33, true), "no change the second time");
        assertFalse(index.isEnclosed(b, -17, 40, -33));

        index.removeContainer(a, -17, 40, -33);
        assertFalse(index.isEnclosed(a, -17, 40, -33));
        assertFalse(index.isContainer(a, -17, 40, -33));
    }

    @Test
    @DisplayName("containers land in the chunk that holds them, negative side included")
    void chunkMembership() {
        HiddenIndex index = new HiddenIndex();
        UUID w = UUID.randomUUID();
        index.addContainer(w, -1, 10, -16);
        assertEquals(Set.of(HiddenIndex.posKey(-1, 10, -16)), index.containersIn(w, -1, -1));
        assertTrue(index.containersIn(w, 0, 0).isEmpty());
    }

    @Test
    @DisplayName("delivery is forgotten on a world change and for containers left behind")
    void deliveryBookkeeping() {
        HiddenIndex index = new HiddenIndex();
        UUID player = UUID.randomUUID();
        long near = HiddenIndex.posKey(1, 64, 1);
        long far = HiddenIndex.posKey(200, 64, 1);
        assertTrue(index.markDelivered(player, near));
        assertFalse(index.markDelivered(player, near), "second delivery is not new");
        index.markDelivered(player, far);

        index.retainDelivered(player, Set.of(near));
        assertTrue(index.isDelivered(player, 1, 64, 1));
        assertFalse(index.isDelivered(player, 200, 64, 1));

        index.clearDelivered(player);
        assertFalse(index.isDelivered(player, 1, 64, 1));
    }
}
