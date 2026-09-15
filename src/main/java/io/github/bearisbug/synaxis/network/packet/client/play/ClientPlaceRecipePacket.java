package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record ClientPlaceRecipePacket(byte windowId, int recipeDisplayId, boolean makeAll) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientPlaceRecipePacket> SERIALIZER = NetworkBufferTemplate.template(
            BYTE, ClientPlaceRecipePacket::windowId,
            VAR_INT, ClientPlaceRecipePacket::recipeDisplayId,
            BOOLEAN, ClientPlaceRecipePacket::makeAll,
            ClientPlaceRecipePacket::new);
}
