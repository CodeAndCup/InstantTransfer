package fr.cupcode.instanttransfer.switching;

import java.util.Iterator;
import java.util.Set;

/**
 * Bounds loadedChunks growth: entries farther than slack Chebyshev distance from the player's
 * chunk can no longer matter for ghost cleanup (the client dropped them long ago; the
 * destination never resends them). O(n) per far teleport only — teleports are rare.
 */
public final class LoadedChunkPruner {

    private LoadedChunkPruner() {}

    public static void prune(Set<Long> loaded, int centerX, int centerZ, int slack) {
        Iterator<Long> it = loaded.iterator();
        while (it.hasNext()) {
            long k = it.next();
            int dx = Math.abs((int) k - centerX);
            int dz = Math.abs((int) (k >> 32) - centerZ);
            if (dx > slack || dz > slack) it.remove();
        }
    }
}
