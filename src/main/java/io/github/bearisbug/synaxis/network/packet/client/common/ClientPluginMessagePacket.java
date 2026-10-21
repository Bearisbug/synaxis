package io.github.bearisbug.synaxis.network.packet.client.common;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.utils.validate.Check;

import java.util.Arrays;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.RAW_BYTES;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;

/**
 * A plugin message from the client, on the configuration or the play state.
 *
 * @param channel the channel of the message
 * @param data    the payload; the accessor returns the storage of the packet, shared by every
 *                listener that receives it, so do not modify it and copy it first to make changes
 */
public record ClientPluginMessagePacket(String channel, byte[] data) implements ClientPacket.Configuration, ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientPluginMessagePacket> SERIALIZER = NetworkBufferTemplate.template(
            STRING, ClientPluginMessagePacket::channel,
            RAW_BYTES, ClientPluginMessagePacket::data,
            ClientPluginMessagePacket::new);

    public ClientPluginMessagePacket {
        if (channel.length() > 256)
            throw new IllegalArgumentException("Channel cannot be more than 256 characters long");
        Check.argCondition(data.length > Short.MAX_VALUE, "Data cannot be longer than Short.MAX_VALUE bytes");
        data = data.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ClientPluginMessagePacket(String channel1, byte[] data1))) return false;
        return Arrays.equals(data(), data1) && channel().equals(channel1);
    }

    @Override
    public int hashCode() {
        int result = channel().hashCode();
        result = 31 * result + Arrays.hashCode(data());
        return result;
    }
}
