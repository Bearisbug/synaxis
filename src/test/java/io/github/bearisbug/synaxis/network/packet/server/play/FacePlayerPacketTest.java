package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.play.FacePlayerPacket.FacePosition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The entity part of the packet, the entity id and the side of it to face, is there only when the
 * flag in front of it says so; an entity id of 0 faces the target alone.
 */
public class FacePlayerPacketTest {

    private static final Vec TARGET = new Vec(1, 2, 3);

    @Test
    public void anEntityToFaceNeedsTheSideToFace() {
        final var error = assertThrows(IllegalArgumentException.class,
                () -> new FacePlayerPacket(FacePosition.EYES, TARGET, 5, null));
        assertEquals("entityFacePosition is required when entityId is 5", error.getMessage());
    }

    @Test
    public void withoutTheEntityFlagNothingMoreIsRead() {
        final byte[] wire = NetworkBuffer.makeArray(buffer -> {
            buffer.write(NetworkBuffer.Enum(FacePosition.class), FacePosition.EYES);
            buffer.write(NetworkBuffer.VECTOR3D, TARGET);
            buffer.write(NetworkBuffer.BOOLEAN, false);
            buffer.write(NetworkBuffer.BYTE, (byte) 1); // whatever follows the packet in the buffer
        });
        final NetworkBuffer buffer = NetworkBuffer.wrap(wire, 0, wire.length);

        final FacePlayerPacket packet = buffer.read(FacePlayerPacket.SERIALIZER);
        assertEquals(new FacePlayerPacket(FacePosition.EYES, TARGET, 0, null), packet);
        assertNull(packet.entityFacePosition());
        assertEquals(1, buffer.readableBytes(), "the byte behind the packet is left unread");
    }

    @Test
    public void theEntityPartIsReadBehindItsFlag() {
        final FacePlayerPacket packet = new FacePlayerPacket(FacePosition.FEET, TARGET, 10, FacePosition.EYES);
        final byte[] wire = NetworkBuffer.makeArray(FacePlayerPacket.SERIALIZER, packet);
        final NetworkBuffer buffer = NetworkBuffer.wrap(wire, 0, wire.length);

        assertEquals(packet, buffer.read(FacePlayerPacket.SERIALIZER));
        assertEquals(0, buffer.readableBytes());
    }
}
