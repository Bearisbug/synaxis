package dev.gameengine.network.packet.client.play;

import dev.gameengine.coordinate.Point;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;
import dev.gameengine.utils.validate.Check;

import static dev.gameengine.network.NetworkBuffer.BLOCK_POSITION;
import static dev.gameengine.network.NetworkBuffer.BYTE;
import static dev.gameengine.network.NetworkBuffer.Enum;
import static dev.gameengine.network.NetworkBuffer.STRING;

public record ClientUpdateCommandBlockPacket(Point blockPosition, String command,
                                             Mode mode, byte flags) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientUpdateCommandBlockPacket> SERIALIZER = NetworkBufferTemplate.template(
            BLOCK_POSITION, ClientUpdateCommandBlockPacket::blockPosition,
            STRING, ClientUpdateCommandBlockPacket::command,
            Enum(Mode.class), ClientUpdateCommandBlockPacket::mode,
            BYTE, ClientUpdateCommandBlockPacket::flags,
            ClientUpdateCommandBlockPacket::new);

    public ClientUpdateCommandBlockPacket {
        Check.argCondition(command.length() > Short.MAX_VALUE, "Command length cannot be greater than Short.MAX_VALUE");
    }

    public enum Mode {
        SEQUENCE, AUTO, REDSTONE
    }
}
