package io.github.bearisbug.synaxis.network.packet.server.common;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import java.util.Arrays;
import java.util.Objects;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.RAW_BYTES;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;

/**
 * A custom payload sent to the client.
 *
 * @param channel the channel
 * @param data    the payload, at most {@link #MAX_PAYLOAD_BYTES}
 */
public record PluginMessagePacket(String channel,
                                  byte[] data) implements ServerPacket.Configuration, ServerPacket.Play {
    /**
     * The largest payload a client accepts, that of the vanilla client-bound custom payload packet:
     * a client disconnects on a larger one, while the packet size limit of the engine is higher.
     */
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;

    public static final NetworkBuffer.Type<PluginMessagePacket> SERIALIZER = NetworkBufferTemplate.template(
            STRING, PluginMessagePacket::channel,
            RAW_BYTES, PluginMessagePacket::data,
            PluginMessagePacket::new);

    /**
     * Creates the packet with a copy of the payload.
     *
     * @throws IllegalArgumentException if {@code data} is over {@link #MAX_PAYLOAD_BYTES}
     */
    public PluginMessagePacket {
        if (data.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Plugin message on " + channel + " is " + data.length
                    + " bytes, over the " + MAX_PAYLOAD_BYTES + " a vanilla client accepts");
        }
        data = data.clone();
    }

    /**
     * Gets the current server brand name packet.
     * <p>
     * Sent to all players when the name changes.
     *
     * @return the current brand name packet
     */
    public static PluginMessagePacket brandPacket(String brandName) {
        final byte[] data = NetworkBuffer.makeArray(STRING, brandName);
        return new PluginMessagePacket("minecraft:brand", data);
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof PluginMessagePacket(String channel1, byte[] data1))) return false;
        return Arrays.equals(data(), data1) && Objects.equals(channel(), channel1);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(channel());
        result = 31 * result + Arrays.hashCode(data());
        return result;
    }
}
