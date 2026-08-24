package fr.cupcode.instanttransfer.protocol;

import fr.cupcode.instanttransfer.switching.SwitchState;
import io.netty.buffer.ByteBuf;

/**
 * Reads chunkX/chunkZ straight off a CHUNK_DATA body without constructing the full wrapper
 * (whose constructor decodes sections/light — the single heaviest recurring cost in the
 * listener). Layout since 1.14: [int chunkX][int chunkZ]...; a leading packet-id byte may be
 * present depending on the packetevents slicing point, so callers use detectHeaderOffset().
 * Any anomaly yields the -1 sentinel; the caller then falls back to the wrapper path.
 *
 * <p>Packing delegates to {@link SwitchState#chunkKey(int, int)} so peeked keys are directly
 * interchangeable with every {@code loadedChunks}/{@code ghostChunks} consumer.</p>
 */
public final class ChunkCoordPeek {

    /** |chunkX|,|chunkZ| stay far below 2^21 (world edge 30M / 16). */
    private static final int COORD_BOUND = 1 << 21;

    private ChunkCoordPeek() {}

    /** @return packed key or -1 when unreadable/out-of-bounds. */
    public static long peek(ByteBuf body, int headerOffset) {
        try {
            if (body == null || headerOffset < 0
                    || body.readableBytes() < headerOffset + 8) {
                return -1L;
            }
            int x = body.getInt(body.readerIndex() + headerOffset);
            int z = body.getInt(body.readerIndex() + headerOffset + 4);
            if (Math.abs(x) > COORD_BOUND || Math.abs(z) > COORD_BOUND) {
                return -1L;
            }
            return key(x, z);
        } catch (Exception e) {
            return -1L;
        }
    }

    /** One-shot: tries offset 0 then 1, validating plausibility of the pair. */
    public static int detectHeaderOffset(ByteBuf body) {
        if (peek(body, 0) != -1L) return 0;
        if (peek(body, 1) != -1L) return 1;
        return -1;
    }

    /** Delegates to {@link SwitchState#chunkKey(int, int)} — single source of truth for packing. */
    public static long key(int x, int z) {
        return SwitchState.chunkKey(x, z);
    }

    /** @return x half of a packed key ({@link SwitchState#chunkX(long)} packing). */
    public static int chunkX(long key) {
        return SwitchState.chunkX(key);
    }

    /** @return z half of a packed key ({@link SwitchState#chunkZ(long)} packing). */
    public static int chunkZ(long key) {
        return SwitchState.chunkZ(key);
    }
}
