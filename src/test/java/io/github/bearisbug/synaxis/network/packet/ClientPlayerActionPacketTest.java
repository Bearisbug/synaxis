package io.github.bearisbug.synaxis.network.packet;

import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerActionPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientPlayerActionPacketTest {

    @Test
    void outOfRangeBlockFaceIsRejected() {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(64);
        buffer.write(NetworkBuffer.VAR_INT, 0); // status
        buffer.write(NetworkBuffer.BLOCK_POSITION, new Vec(0, 0, 0));
        buffer.write(NetworkBuffer.BYTE, (byte) 127); // only six block faces exist
        buffer.write(NetworkBuffer.VAR_INT, 0); // sequence

        assertThrows(IllegalArgumentException.class, () -> buffer.read(ClientPlayerActionPacket.SERIALIZER));
    }
}
