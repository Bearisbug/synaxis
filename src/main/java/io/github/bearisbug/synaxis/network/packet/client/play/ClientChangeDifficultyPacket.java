package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.world.Difficulty;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.Enum;

public record ClientChangeDifficultyPacket(Difficulty difficulty, boolean locked) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientChangeDifficultyPacket> SERIALIZER = NetworkBufferTemplate.template(
            Enum(Difficulty.class), ClientChangeDifficultyPacket::difficulty,
            BOOLEAN, ClientChangeDifficultyPacket::locked,
            ClientChangeDifficultyPacket::new);
}
