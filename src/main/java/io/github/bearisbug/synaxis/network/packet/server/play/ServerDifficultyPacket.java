package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.world.Difficulty;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.Enum;

public record ServerDifficultyPacket(Difficulty difficulty, boolean locked) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<ServerDifficultyPacket> SERIALIZER = NetworkBufferTemplate.template(
            Enum(Difficulty.class), ServerDifficultyPacket::difficulty,
            BOOLEAN, ServerDifficultyPacket::locked,
            ServerDifficultyPacket::new);
}
