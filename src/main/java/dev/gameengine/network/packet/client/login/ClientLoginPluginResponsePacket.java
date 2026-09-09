package dev.gameengine.network.packet.client.login;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

import static dev.gameengine.network.NetworkBuffer.RAW_BYTES;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record ClientLoginPluginResponsePacket(int messageId, byte @Nullable [] data) implements ClientPacket.Login {
    public static final NetworkBuffer.Type<ClientLoginPluginResponsePacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientLoginPluginResponsePacket::messageId,
            RAW_BYTES.optional(), ClientLoginPluginResponsePacket::data,
            ClientLoginPluginResponsePacket::new);

    public ClientLoginPluginResponsePacket {
        data = data != null ? data.clone() : null;
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof ClientLoginPluginResponsePacket(int id, byte[] data1))) return false;
        return messageId() == id && Arrays.equals(data(), data1);
    }

    @Override
    public int hashCode() {
        int result = messageId();
        result = 31 * result + Arrays.hashCode(data());
        return result;
    }
}
