package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;
import dev.gameengine.world.Difficulty;

import static dev.gameengine.network.NetworkBuffer.BOOLEAN;
import static dev.gameengine.network.NetworkBuffer.Enum;

public record ServerDifficultyPacket(Difficulty difficulty, boolean locked) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<ServerDifficultyPacket> SERIALIZER = NetworkBufferTemplate.template(
            Enum(Difficulty.class), ServerDifficultyPacket::difficulty,
            BOOLEAN, ServerDifficultyPacket::locked,
            ServerDifficultyPacket::new);
}
