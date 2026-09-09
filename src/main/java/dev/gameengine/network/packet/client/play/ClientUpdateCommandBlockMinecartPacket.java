package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;
import dev.gameengine.utils.validate.Check;

import static dev.gameengine.network.NetworkBuffer.BOOLEAN;
import static dev.gameengine.network.NetworkBuffer.STRING;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

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
