package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.FLOAT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record SetExperiencePacket(float percentage, int level, int totalExperience) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<SetExperiencePacket> SERIALIZER = NetworkBufferTemplate.template(
            FLOAT, SetExperiencePacket::percentage,
            VAR_INT, SetExperiencePacket::level,
            VAR_INT, SetExperiencePacket::totalExperience,
            SetExperiencePacket::new);
}
