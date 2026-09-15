package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.LightData;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record UpdateLightPacket(int chunkX, int chunkZ,
                                LightData lightData) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<UpdateLightPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, UpdateLightPacket::chunkX,
            VAR_INT, UpdateLightPacket::chunkZ,
            LightData.NETWORK_TYPE, UpdateLightPacket::lightData,
            UpdateLightPacket::new
    );
}
