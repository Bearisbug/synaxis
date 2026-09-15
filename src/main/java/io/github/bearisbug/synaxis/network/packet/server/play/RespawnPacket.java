package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.PlayerSpawnInfo;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;

public record RespawnPacket(PlayerSpawnInfo playerSpawnInfo, byte copyData) implements ServerPacket.Play {
    public static final int COPY_NONE = 0x0;
    public static final int COPY_ATTRIBUTES = 0x1;
    public static final int COPY_METADATA = 0x2;
    public static final int COPY_ALL = COPY_ATTRIBUTES | COPY_METADATA;

    public static final NetworkBuffer.Type<RespawnPacket> SERIALIZER = NetworkBufferTemplate.template(
            PlayerSpawnInfo.NETWORK_TYPE, RespawnPacket::playerSpawnInfo,
            BYTE, RespawnPacket::copyData,
            RespawnPacket::new);

}
