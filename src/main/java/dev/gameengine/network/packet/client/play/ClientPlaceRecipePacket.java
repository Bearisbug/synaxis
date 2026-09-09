package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.BOOLEAN;
import static dev.gameengine.network.NetworkBuffer.BYTE;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record ClientPlaceRecipePacket(byte windowId, int recipeDisplayId, boolean makeAll) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientPlaceRecipePacket> SERIALIZER = NetworkBufferTemplate.template(
            BYTE, ClientPlaceRecipePacket::windowId,
            VAR_INT, ClientPlaceRecipePacket::recipeDisplayId,
            BOOLEAN, ClientPlaceRecipePacket::makeAll,
            ClientPlaceRecipePacket::new);
}
