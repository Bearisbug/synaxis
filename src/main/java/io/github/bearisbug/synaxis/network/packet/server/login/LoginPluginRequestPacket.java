package io.github.bearisbug.synaxis.network.packet.server.login;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import java.util.Arrays;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.RAW_BYTES;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record LoginPluginRequestPacket(int messageId, String channel,
                                       byte[] data) implements ServerPacket.Login {
    public static final NetworkBuffer.Type<LoginPluginRequestPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, LoginPluginRequestPacket::messageId,
            STRING, LoginPluginRequestPacket::channel,
            RAW_BYTES, LoginPluginRequestPacket::data,
            LoginPluginRequestPacket::new);

    public LoginPluginRequestPacket {
        data = data.clone();
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof LoginPluginRequestPacket(int messageId1, String channel1, byte[] data1))) return false;
        return messageId() == messageId1 && Arrays.equals(data(), data1) && channel().equals(channel1);
    }

    @Override
    public int hashCode() {
        int result = messageId();
        result = 31 * result + channel().hashCode();
        result = 31 * result + Arrays.hashCode(data());
        return result;
    }
}
