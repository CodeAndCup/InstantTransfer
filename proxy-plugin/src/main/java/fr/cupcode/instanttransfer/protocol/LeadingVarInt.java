package fr.cupcode.instanttransfer.protocol;

import io.netty.buffer.ByteBuf;

/**
 * Reads the leading entity-id VarInt of movement-family packets without building the wrapper.
 * Every supported protocol (1.13 → latest) encodes these packets with the entity id first.
 * Returns -1 on anything unexpected; callers fall back to wrapper construction (always
 * correct, merely slower).
 */
public final class LeadingVarInt {

    public static final int SENTINEL = -1;
    /** Entity ids are non-negative 32-bit values; cap sanity well below overflow. */
    private static final int MAX_ID = Integer.MAX_VALUE;

    private LeadingVarInt() {}

    public static int peek(ByteBuf buf, int offset) {
        try {
            if (buf == null || offset < 0) return SENTINEL;
            int idx = buf.readerIndex() + offset;
            if (idx >= buf.writerIndex()) return SENTINEL;
            int value = 0, shift = 0, read = 0;
            while (shift < 32) {
                if (idx + read >= buf.writerIndex()) return SENTINEL;
                byte b = buf.getByte(idx + read++);
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    if (read > 5) return SENTINEL;          // malformed length
                    if (value < 0 || value > MAX_ID) return SENTINEL;
                    return value;
                }
                shift += 7;
            }
            return SENTINEL;
        } catch (Exception e) {
            return SENTINEL;
        }
    }
}
