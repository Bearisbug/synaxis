package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.utils.validate.Check;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record ClientUpdateCommandBlockMinecartPacket(int entityId, String command,
                                                     boolean trackOutput) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientUpdateCommandBlockMinecartPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientUpdateCommandBlockMinecartPacket::entityId,
            STRING, ClientUpdateCommandBlockMinecartPacket::command,
            BOOLEAN, ClientUpdateCommandBlockMinecartPacket::trackOutput,
            ClientUpdateCommandBlockMinecartPacket::new);

    public ClientUpdateCommandBlockMinecartPacket {
        Check.argCondition(command.length() > Short.MAX_VALUE, "Command length cannot be greater than Short.MAX_VALUE");
    }
}
