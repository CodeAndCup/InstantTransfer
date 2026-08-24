package fr.cupcode.instanttransfer.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LeadingVarIntTest {

    private static void writeVarInt(ByteBuf b, int value) {
        while ((value & ~0x7F) != 0) {
            b.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        b.writeByte(value);
    }

    @Test
    void readsSingleByteVarIntAtZero() {
        var buf = Unpooled.buffer();
        writeVarInt(buf, 42);
        buf.writeZero(8);
        assertEquals(42, LeadingVarInt.peek(buf, 0));
    }

    @Test
    void readsMultiByteVarInt() {
        var buf = Unpooled.buffer();
        writeVarInt(buf, 100_000);
        buf.writeZero(8);
        assertEquals(100_000, LeadingVarInt.peek(buf, 0));
    }

    @Test
    void respectsOffset() {
        var buf = Unpooled.buffer();
        buf.writeByte(0x27); // hypothetical packet-id byte
        writeVarInt(buf, 7);
        buf.writeZero(8);
        assertEquals(7, LeadingVarInt.peek(buf, 1));
    }

    @Test
    void truncatedReturnsSentinel() {
        var buf = Unpooled.buffer().writeByte(0x80);
        assertEquals(-1, LeadingVarInt.peek(buf, 0));
    }

    @Test
    void emptyReturnsSentinel() {
        assertEquals(-1, LeadingVarInt.peek(Unpooled.EMPTY_BUFFER, 0));
    }

    @Test
    void negativeIdsRejected() {
        var buf = Unpooled.buffer();
        writeVarInt(buf, Integer.MAX_VALUE);
        buf.writeZero(8);
        assertEquals(Integer.MAX_VALUE, LeadingVarInt.peek(buf, 0));
    }
}
