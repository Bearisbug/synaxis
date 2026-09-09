package net.minestom.server.network.packet;

import net.minestom.server.coordinate.Vec;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.client.play.ClientPlayerActionPacket;
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
