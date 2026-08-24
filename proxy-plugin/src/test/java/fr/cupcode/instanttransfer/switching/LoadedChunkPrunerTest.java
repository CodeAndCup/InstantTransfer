package fr.cupcode.instanttransfer.switching;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static fr.cupcode.instanttransfer.protocol.ChunkCoordPeek.key;
import static org.junit.jupiter.api.Assertions.*;

class LoadedChunkPrunerTest {

    @Test
    void keepsChunksWithinSlack() {
        Set<Long> chunks = ConcurrentHashMap.newKeySet();
        chunks.add(key(0, 0));
        chunks.add(key(10, 10));
        chunks.add(key(40, 0));   // outside slack 32
        LoadedChunkPruner.prune(chunks, 0, 0, 32);
        assertTrue(chunks.contains(key(0, 0)));
        assertTrue(chunks.contains(key(10, 10)));
        assertFalse(chunks.contains(key(40, 0)));
    }

    @Test
    void prunesAroundNewCenter() {
        Set<Long> chunks = ConcurrentHashMap.newKeySet();
        chunks.add(key(1000, 1000));
        LoadedChunkPruner.prune(chunks, 0, 0, 32);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void matchesPackingAxesAroundAsymmetricCenter() {
        Set<Long> chunks = ConcurrentHashMap.newKeySet();
        chunks.add(key(40, 0));   // far in x only, relative to center (0,40) -> pruned
        chunks.add(key(0, 40));   // at the center's z, x aligned -> kept
        LoadedChunkPruner.prune(chunks, 0, 40, 32);
        assertFalse(chunks.contains(key(40, 0)));
        assertTrue(chunks.contains(key(0, 40)));
    }

    @Test
    void handlesNegativeCoordinates() {
        Set<Long> chunks = ConcurrentHashMap.newKeySet();
        chunks.add(key(-30, -30));
        chunks.add(key(-70, -70));   // Chebyshev distance 37 > slack 32
        LoadedChunkPruner.prune(chunks, -33, -35, 32);
        assertTrue(chunks.contains(key(-30, -30)));
        assertFalse(chunks.contains(key(-70, -70)));
    }
}
