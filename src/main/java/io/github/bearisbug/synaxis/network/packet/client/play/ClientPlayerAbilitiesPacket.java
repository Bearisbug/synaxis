package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;

public record ClientPlayerAbilitiesPacket(byte flags) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientPlayerAbilitiesPacket> SERIALIZER = NetworkBufferTemplate.template(
            BYTE, ClientPlayerAbilitiesPacket::flags,
            ClientPlayerAbilitiesPacket::new);
}
