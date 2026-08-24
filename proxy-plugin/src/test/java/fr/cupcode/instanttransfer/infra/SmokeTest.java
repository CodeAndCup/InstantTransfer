package fr.cupcode.instanttransfer.infra;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmokeTest {
    @Test
    void nettyAndJunitAreOnTestClasspath() {
        assertEquals(4, Unpooled.buffer().writeInt(4).readableBytes());
    }
}
