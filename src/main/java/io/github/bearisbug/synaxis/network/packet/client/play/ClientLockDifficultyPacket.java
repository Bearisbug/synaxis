package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;

public record ClientLockDifficultyPacket(boolean locked) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientLockDifficultyPacket> SERIALIZER = NetworkBufferTemplate.template(
            BOOLEAN, ClientLockDifficultyPacket::locked,
            ClientLockDifficultyPacket::new);
}
