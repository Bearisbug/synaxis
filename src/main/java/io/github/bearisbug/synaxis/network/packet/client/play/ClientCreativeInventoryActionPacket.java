package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.SHORT;

public record ClientCreativeInventoryActionPacket(short slot, ItemStack item) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientCreativeInventoryActionPacket> SERIALIZER = NetworkBufferTemplate.template(
            SHORT, ClientCreativeInventoryActionPacket::slot,
            ItemStack.UNTRUSTED_NETWORK_TYPE, ClientCreativeInventoryActionPacket::item,
            ClientCreativeInventoryActionPacket::new);
}
