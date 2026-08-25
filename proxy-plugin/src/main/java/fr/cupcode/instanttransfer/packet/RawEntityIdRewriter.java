package fr.cupcode.instanttransfer.packet;

import fr.cupcode.instanttransfer.switching.SwitchState;
import io.netty.buffer.ByteBuf;

/**
 * Rewrites the leading entity-id VarInt of a server->client packet DIRECTLY in the event
 * buffer, without constructing any packetevents wrapper.
 *
 * <p>Why raw: on this Paper 1.21.4 network the PE 2.13.0 event buffers sometimes still carry
 * the packet-id VarInt prefix (empirically proven by the chunk-header offset probe), which
 * misaligns every wrapper parse by one byte — and a misaligned parse that still "succeeds"
 * re-encodes into a TRUNCATED frame that hard-crashes the vanilla decoder (observed on
 * set_entity_data / set_entity_motion / entity_position_sync).</p>
 *
 * <p>Safety contract: the full original bytes are saved before any mutation; the rewrite is
 * verified by re-reading; ANY anomaly restores the original bytes and reports failure — a
 * failed rewrite degrades to pass-through (the backend id reaches the client and is ignored),
 * never to a corrupted frame.</p>
 */
final class RawEntityIdRewriter {

    private RawEntityIdRewriter() {}

    /**
     * Attempts an in-place rewrite backendId -> clientId.
     *
     * @return true when the packet was rewritten (the mutated buffer is forwarded as-is: no
     *         wrapper owns it, so nothing re-encodes), false when untouched (id not ours,
     *         unreadable, or any anomaly — original bytes restored).
     */
    static boolean rewriteSelfId(ByteBuf buf, SwitchState state) {
        final int readerIndex = buf.readerIndex();
        final int writerIndex = buf.writerIndex();
        final byte[] original = snapshot(buf, readerIndex, writerIndex);
        if (original == null) {
            return false;
        }
        try {
            // Optional packet-id VarInt prefix (presence varies with slicing): test both
            // hypotheses — first varint IS the entity id, or it is a pid followed by the entity
            // id. Pick the hypothesis whose candidate equals backendId.
            int[] candidates = {-1, -1};
            int[] idStarts = {-1, -1};
            int[] idEnds = {-1, -1};
            for (int hyp = 0; hyp < 2; hyp++) {
                int p = readerIndex + (hyp == 0 ? 0 : varIntLen(buf, readerIndex));
                if (p < 0 || p >= writerIndex) continue;
                if (varIntSpan(buf, p) == null) continue;
                candidates[hyp] = readVarInt(buf, p);
                idStarts[hyp] = p;
                idEnds[hyp] = varIntSpan(buf, p)[1];
            }
            boolean hypNoPid = candidates[0] == state.backendEntityId();
            boolean hypWithPid = candidates[1] == state.backendEntityId();
            if (!hypNoPid && !hypWithPid) {
                return false; // not targeting the player: nothing to do
            }
            int use = hypNoPid && !hypWithPid ? 0 : 1;
            int idStart = idStarts[use];
            int idEnd = idEnds[use];
            int newId = state.clientEntityId();

            byte[] tail = null;
            int tailLen = writerIndex - idEnd;
            if (tailLen > 0) {
                tail = new byte[tailLen];
                buf.getBytes(idEnd, tail);
            }
            buf.writerIndex(idStart);
            writeVarInt(buf, newId);
            if (tail != null) {
                buf.ensureWritable(tail.length);
                buf.writeBytes(tail);
            }
            // Verify: the rewritten leading region must decode back to newId.
            if (readVarInt(buf, idStart) != newId) {
                throw new IllegalStateException("post-write verify mismatch");
            }
            return true;
        } catch (Exception any) {
            lastFailure = any; // diagnostic seam (tests/debug); production ignores
            // Roll back to the exact original bytes: never emit a half-mutated frame.
            buf.setBytes(readerIndex, original);
            buf.writerIndex(readerIndex + original.length);
            buf.readerIndex(readerIndex);
            return false;
        }
    }

    /** Last exception that triggered a rollback — diagnostic only. */
    static Exception lastFailure() {
        return lastFailure;
    }

    private static Exception lastFailure;

    private static byte[] snapshot(ByteBuf buf, int from, int to) {
        if (to < from || from < 0) return null;
        byte[] out = new byte[to - from];
        buf.getBytes(from, out);
        return out;
    }

    /** @return span {start,end-exclusive} of the VarInt at p, or null when malformed/truncated. */
    private static int[] varIntSpan(ByteBuf buf, int p) {
        int shift = 0, i = p;
        while (shift < 32) {
            if (i >= buf.writerIndex()) return null;
            byte b = buf.getByte(i++);
            if ((b & 0x80) == 0) {
                if (i - p > 5) return null;
                return new int[]{p, i};
            }
            shift += 7;
        }
        return null;
    }

    private static int readVarInt(ByteBuf buf, int p) {
        int[] se = varIntSpan(buf, p);
        int value = 0, shift = 0;
        for (int i = se[0]; i < se[1]; i++) {
            value |= (buf.getByte(i) & 0x7F) << shift;
            shift += 7;
        }
        return value;
    }

    private static int varIntLen(ByteBuf buf, int p) {
        int[] se = varIntSpan(buf, p);
        return se == null ? 0 : se[1] - se[0];
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}