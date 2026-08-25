package fr.cupcode.instanttransfer.switching;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PendingChunkUnloadsTest {

    private SwitchState.PendingChunkUnloads p = new SwitchState.PendingChunkUnloads();

    @Test
    void drainedKeysLeaveEmptyAndReturnOnce() {
        p.defer(1L); p.defer(2L); p.defer(3L);
        assertArrayEquals(new long[]{1, 2, 3}, p.drainSorted());
        assertTrue(p.isEmpty());
        assertEquals(0, p.drainSorted().length);
    }

    @Test
    void cancelledKeysDoNotDrain() {
        p.defer(10L); p.defer(20L);
        p.cancelIfPresent(10L);
        assertArrayEquals(new long[]{20}, p.drainSorted());
    }

    @Test
    void duplicateDefersCollapse() {
        p.defer(5L); p.defer(5L);
        assertArrayEquals(new long[]{5}, p.drainSorted());
    }

    @Test
    void cancelUnknownKeyIsNoop() {
        assertDoesNotThrow(() -> p.cancelIfPresent(999L));
        assertTrue(p.isEmpty());
    }

    @Test
    void sortedOutputForDeterministicFlush() {
        p.defer(30L); p.defer(10L); p.defer(20L);
        assertArrayEquals(new long[]{10, 20, 30}, p.drainSorted());
    }

    @Test
    void clearEmptiesPopulatedSet() {
        p.defer(100L); p.defer(200L); p.defer(300L);
        p.cancelIfPresent(200L);
        p.clear();
        assertTrue(p.isEmpty());
        assertEquals(0, p.drainSorted().length);
    }
}
