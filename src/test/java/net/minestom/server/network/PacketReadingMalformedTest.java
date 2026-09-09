package net.minestom.server.network;

import net.minestom.server.network.packet.PacketReading;
import org.junit.jupiter.api.Test;

import java.util.zip.DataFormatException;

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

}
