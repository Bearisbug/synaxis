package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;
import dev.gameengine.world.Difficulty;

import static dev.gameengine.network.NetworkBuffer.BOOLEAN;
import static dev.gameengine.network.NetworkBuffer.Enum;

public record ClientChangeDifficultyPacket(Difficulty difficulty, boolean locked) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientChangeDifficultyPacket> SERIALIZER = NetworkBufferTemplate.template(
            Enum(Difficulty.class), ClientChangeDifficultyPacket::difficulty,
            BOOLEAN, ClientChangeDifficultyPacket::locked,
            ClientChangeDifficultyPacket::new);
}
