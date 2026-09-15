package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record ClientRecipeBookSeenRecipePacket(int index) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientRecipeBookSeenRecipePacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientRecipeBookSeenRecipePacket::index,
            ClientRecipeBookSeenRecipePacket::new);
}
