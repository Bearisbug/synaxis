package dev.gameengine.network.packet.client.play;

import dev.gameengine.coordinate.Point;
import dev.gameengine.coordinate.BlockFace;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;
import dev.gameengine.utils.validate.Check;

import static dev.gameengine.network.NetworkBuffer.BLOCK_POSITION;
import static dev.gameengine.network.NetworkBuffer.BYTE;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record ClientPlayerActionPacket(
        Status status, Point blockPosition,
        BlockFace blockFace, int sequence
) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientPlayerActionPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.Enum(Status.class), ClientPlayerActionPacket::status,
            BLOCK_POSITION, ClientPlayerActionPacket::blockPosition,
            BYTE.transform(ClientPlayerActionPacket::blockFace, blockFace1 -> (byte) blockFace1.ordinal()), ClientPlayerActionPacket::blockFace,
            VAR_INT, ClientPlayerActionPacket::sequence,
            ClientPlayerActionPacket::new);

    private static BlockFace blockFace(byte index) {
        final BlockFace[] faces = BlockFace.values();
        Check.argCondition(index < 0 || index >= faces.length, "Invalid block face: {0}", index);
        return faces[index];
    }

    public enum Status {
        STARTED_DIGGING,
        CANCELLED_DIGGING,
        FINISHED_DIGGING,
        DROP_ITEM_STACK,
        DROP_ITEM,
        UPDATE_ITEM_STATE,
        SWAP_ITEM_HAND,
        STAB,
    }
}
