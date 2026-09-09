package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;
import dev.gameengine.network.packet.server.play.data.LightData;

import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record UpdateLightPacket(int chunkX, int chunkZ,
                                LightData lightData) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<UpdateLightPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, UpdateLightPacket::chunkX,
            VAR_INT, UpdateLightPacket::chunkZ,
            LightData.NETWORK_TYPE, UpdateLightPacket::lightData,
            UpdateLightPacket::new
    );
}
