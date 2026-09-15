package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.utils.validate.Check;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BLOCK_POSITION;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.Enum;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;

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
