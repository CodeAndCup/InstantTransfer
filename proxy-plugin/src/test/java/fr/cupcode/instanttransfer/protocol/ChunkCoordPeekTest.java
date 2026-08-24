package fr.cupcode.instanttransfer.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkCoordPeekTest {

    private static ByteBuf body(int x, int z) {
        return Unpooled.buffer().writeInt(x).writeInt(z).writeBoolean(true).writeZero(16);
    }

    @Test
    void readsCoordsAtOffsetZero() {
        long k = ChunkCoordPeek.peek(body(12, -34), 0);
        assertEquals(12L, ChunkCoordPeek.chunkX(k));
        assertEquals(-34L, ChunkCoordPeek.chunkZ(k));
    }

    @Test
    void readsCoordsAfterOneBytePacketId() {
        ByteBuf b = Unpooled.buffer().writeByte(0x27).writeInt(5).writeInt(6).writeZero(16);
        long k = ChunkCoordPeek.peek(b, 1);
        assertEquals(5L, ChunkCoordPeek.chunkX(k));
        assertEquals(6L, ChunkCoordPeek.chunkZ(k));
    }

    @Test
    void detectsOffsetOnPacketIdPrefixedBody() {
        ByteBuf b = Unpooled.buffer().writeByte(0x27).writeInt(5).writeInt(6).writeZero(16);
        assertEquals(1, ChunkCoordPeek.detectHeaderOffset(b));
    }

    @Test
    void rejectsTruncatedBody() {
        assertEquals(-1L, ChunkCoordPeek.peek(Unpooled.buffer().writeInt(1), 0));
    }

    @Test
    void rejectsOutOfBoundsCoords() {
        assertEquals(-1L, ChunkCoordPeek.peek(body(Integer.MAX_VALUE, 0), 0));
        assertEquals(-1L, ChunkCoordPeek.peek(body(0, -30_000_000), 0));
    }

    @Test
    void negativeSentinelMeansFailure() {
        assertEquals(-1L, ChunkCoordPeek.peek(Unpooled.EMPTY_BUFFER, 0));
    }
}
