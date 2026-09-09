package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.BOOLEAN;

public record ClientLockDifficultyPacket(boolean locked) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientLockDifficultyPacket> SERIALIZER = NetworkBufferTemplate.template(
            BOOLEAN, ClientLockDifficultyPacket::locked,
            ClientLockDifficultyPacket::new);
}
