package dev.gameengine.network.packet.client.play;

import dev.gameengine.item.ItemStack;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.SHORT;

public record ClientCreativeInventoryActionPacket(short slot, ItemStack item) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientCreativeInventoryActionPacket> SERIALIZER = NetworkBufferTemplate.template(
            SHORT, ClientCreativeInventoryActionPacket::slot,
            ItemStack.UNTRUSTED_NETWORK_TYPE, ClientCreativeInventoryActionPacket::item,
            ClientCreativeInventoryActionPacket::new);
}
