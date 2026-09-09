package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record ClientRecipeBookSeenRecipePacket(int index) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientRecipeBookSeenRecipePacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientRecipeBookSeenRecipePacket::index,
            ClientRecipeBookSeenRecipePacket::new);
}
