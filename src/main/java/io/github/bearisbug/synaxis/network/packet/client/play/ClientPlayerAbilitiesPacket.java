package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;

public record ClientPlayerAbilitiesPacket(byte flags) implements ClientPacket.Play {
    private static final byte FLAG_FLYING = 0x02;

    public static final NetworkBuffer.Type<ClientPlayerAbilitiesPacket> SERIALIZER = NetworkBufferTemplate.template(
            BYTE, ClientPlayerAbilitiesPacket::flags,
            ClientPlayerAbilitiesPacket::new);

    public boolean flying() {
        return (flags & FLAG_FLYING) != 0;
    }
}
