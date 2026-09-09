package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.INT;

public record SetTitleTimePacket(int fadeIn, int stay, int fadeOut) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<SetTitleTimePacket> SERIALIZER = NetworkBufferTemplate.template(
            INT, SetTitleTimePacket::fadeIn,
            INT, SetTitleTimePacket::stay,
            INT, SetTitleTimePacket::fadeOut,
            SetTitleTimePacket::new);
}
