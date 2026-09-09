package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.FLOAT;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record SetExperiencePacket(float percentage, int level, int totalExperience) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<SetExperiencePacket> SERIALIZER = NetworkBufferTemplate.template(
            FLOAT, SetExperiencePacket::percentage,
            VAR_INT, SetExperiencePacket::level,
            VAR_INT, SetExperiencePacket::totalExperience,
            SetExperiencePacket::new);
}
