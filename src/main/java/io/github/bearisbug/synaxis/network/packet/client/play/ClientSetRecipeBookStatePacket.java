package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;

public record ClientSetRecipeBookStatePacket(BookType bookType,
                                             boolean bookOpen, boolean filterActive) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientSetRecipeBookStatePacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.Enum(BookType.class), ClientSetRecipeBookStatePacket::bookType,
            BOOLEAN, ClientSetRecipeBookStatePacket::bookOpen,
            BOOLEAN, ClientSetRecipeBookStatePacket::filterActive,
            ClientSetRecipeBookStatePacket::new);

    public enum BookType {
        CRAFTING, FURNACE, BLAST_FURNACE, SMOKER
    }
}
