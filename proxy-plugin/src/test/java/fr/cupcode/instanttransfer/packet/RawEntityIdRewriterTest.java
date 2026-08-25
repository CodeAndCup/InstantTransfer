package fr.cupcode.instanttransfer.packet;

import fr.cupcode.instanttransfer.protocol.LeadingVarInt;


import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RawEntityIdRewriterTest {

    

    // SwitchState is heavyweight; the rewriter only reads two ints from it — use a tiny stub
    // via reflection-free approach: rewriteSelfId takes SwitchState, so build one through its
    // public API if cheap, else test the math through a package-local seam. Simplest: construct
    // real instance if it has a no-arg/uuid ctor.
    private static fr.cupcode.instanttransfer.switching.SwitchState state(int backend, int client)
            throws Exception {
        fr.cupcode.instanttransfer.switching.SwitchState s =
                new fr.cupcode.instanttransfer.switching.SwitchState();
        s.entityIds(client, backend);
        return s;
    }

    private static void writeVarInt(io.netty.buffer.ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    @Test
    void rewritesWithoutPidPrefix() throws Exception {
        var buf = Unpooled.buffer();
        writeVarInt(buf, 21);          // backend entity id
        buf.writeZero(10);             // payload
        boolean ok = RawEntityIdRewriter.rewriteSelfId(buf, state(21, 7));
        if (!ok) { System.out.println("rewrite failed: " + RawEntityIdRewriter.lastFailure()); }
        assertTrue(ok);
        assertEquals(7, LeadingVarInt.peek(buf, 0));
        assertEquals(11, buf.writerIndex()); // payload preserved
    }

    @Test
    void rewritesWithPidPrefix() throws Exception {
        var buf = Unpooled.buffer();
        writeVarInt(buf, 0x5B);        // packet id (2-byte varint to exercise length shift)
        buf.writeByte(0xE0);           // continuation byte of the 2-byte pid varint... simplified
        buf.resetReaderIndex().resetWriterIndex();
        writeVarInt(buf, 200);         // packet id (2-byte varint)
        writeVarInt(buf, 21);          // entity id
        buf.writeZero(6);
        boolean ok = RawEntityIdRewriter.rewriteSelfId(buf, state(21, 7));
        if (!ok) { System.out.println("rewrite failed: " + RawEntityIdRewriter.lastFailure()); }
        assertTrue(ok);
        assertEquals(200, LeadingVarInt.peek(buf, 0));          // pid untouched
        assertEquals(7, LeadingVarInt.peek(buf, 2));            // rewritten id after 2-byte pid
        assertEquals(9, buf.writerIndex());                     // grew by length delta
    }

    @Test
    void leavesForeignEntityUntouched() throws Exception {
        var before = new byte[]{5, 0, 0, 0};
        var buf = Unpooled.buffer().writeBytes(before);
        assertFalse(RawEntityIdRewriter.rewriteSelfId(buf, state(21, 7)));
        assertArrayEquals(before, java.util.Arrays.copyOf(
                buf.array(), before.length));
    }

    @Test
    void bareVarintIdIsStillRewritable() throws Exception {
        // A single-byte frame is a COMPLETE (if odd) packet for the rewriter: the swap only
        // touches the leading VarInt, so it must succeed and produce the client id.
        var buf = Unpooled.buffer().writeByte(0x15); // 21
        assertTrue(RawEntityIdRewriter.rewriteSelfId(buf, state(21, 7)));
        assertEquals(7, LeadingVarInt.peek(buf, 0));
        assertEquals(1, buf.writerIndex());
    }

    @Test
    void truncatedTailDoesNotPreventLeadingSwap() throws Exception {
        // id + one payload byte: rewrite touches ONLY the id; tail byte survives.
        var buf = Unpooled.buffer();
        writeVarInt(buf, 21);
        buf.writeByte(0xAB);
        assertTrue(RawEntityIdRewriter.rewriteSelfId(buf, state(21, 7)));
        assertEquals(7, LeadingVarInt.peek(buf, 0));
        assertEquals(0xAB, buf.getByte(1) & 0xFF);
    }
}
