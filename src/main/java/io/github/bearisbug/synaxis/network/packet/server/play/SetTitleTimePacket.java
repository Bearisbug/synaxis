package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.INT;

public record SetTitleTimePacket(int fadeIn, int stay, int fadeOut) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<SetTitleTimePacket> SERIALIZER = NetworkBufferTemplate.template(
            INT, SetTitleTimePacket::fadeIn,
            INT, SetTitleTimePacket::stay,
            INT, SetTitleTimePacket::fadeOut,
            SetTitleTimePacket::new);
}
