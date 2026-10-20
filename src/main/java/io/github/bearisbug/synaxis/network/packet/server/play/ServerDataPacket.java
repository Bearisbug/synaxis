package io.github.bearisbug.synaxis.network.packet.server.play;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE_ARRAY;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.COMPONENT;

/**
 * Updates the message of the day and the icon the client keeps for the server it is connected to.
 *
 * @param motd       the message of the day
 * @param iconBase64 the raw PNG bytes of the icon (not Base64), or {@code null} for none
 */
public record ServerDataPacket(Component motd, byte @Nullable [] iconBase64) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<ServerDataPacket> SERIALIZER = NetworkBufferTemplate.template(
            COMPONENT, ServerDataPacket::motd,
            BYTE_ARRAY.optional(), ServerDataPacket::iconBase64,
            ServerDataPacket::new);

    public ServerDataPacket {
        iconBase64 = iconBase64 != null ? iconBase64.clone() : null;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ServerDataPacket(Component motd1, byte[] base64))) return false;
        return motd().equals(motd1) && Arrays.equals(iconBase64(), base64);
    }

    @Override
    public int hashCode() {
        int result = motd().hashCode();
        result = 31 * result + Arrays.hashCode(iconBase64());
        return result;
    }
}
