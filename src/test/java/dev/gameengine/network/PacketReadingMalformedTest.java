package dev.gameengine.network;

import dev.gameengine.network.packet.PacketReading;
import org.junit.jupiter.api.Test;

import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Malformed frame headers must be reported as protocol errors instead of leaking
 * internal exceptions to the read loop (issue #3320).
 */
class PacketReadingMalformedTest {

    @Test
    void zeroLengthFrameIsAProtocolError() {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(64);
        buffer.write(NetworkBuffer.VAR_INT, 0); // single 0x00 byte
        assertThrows(DataFormatException.class,
                () -> PacketReading.readClients(buffer, ConnectionState.HANDSHAKE, false));
    }

    @Test
    void overlongVarIntLengthIsAProtocolError() {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(64);
        for (int i = 0; i < 5; i++) buffer.write(NetworkBuffer.BYTE, (byte) 0xFF);
        assertThrows(DataFormatException.class,
                () -> PacketReading.readClients(buffer, ConnectionState.HANDSHAKE, false));
    }

    @Test
    void truncatedVarIntLengthAtBufferEndRequiresABiggerBuffer() throws DataFormatException {
        // Only a partial var-int fits before the end of the segment, the caller must be
        // asked for a capacity strictly greater than the current one.
        final NetworkBuffer buffer = NetworkBuffer.staticBuffer(8);
        buffer.writeIndex(4).readIndex(4);
        for (int i = 0; i < 4; i++) buffer.write(NetworkBuffer.BYTE, (byte) 0xFF);

        final var result = PacketReading.readClients(buffer, ConnectionState.HANDSHAKE, false);
        final var failure = assertInstanceOf(PacketReading.Result.Failure.class, result);
        assertEquals(4 + 5, failure.requiredCapacity());
    }
}
