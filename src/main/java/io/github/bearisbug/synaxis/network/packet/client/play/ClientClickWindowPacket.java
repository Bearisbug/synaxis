package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import java.util.Map;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.Enum;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.SHORT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record ClientClickWindowPacket(int windowId, int stateId,
                                      short slot, byte button, ClickType clickType,
                                      Map<Short, ItemStack.Hash> changedSlots,
                                      ItemStack.Hash clickedItem) implements ClientPacket.Play {
    public static final int MAX_CHANGED_SLOTS = 128;

    public static final NetworkBuffer.Type<ClientClickWindowPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientClickWindowPacket::windowId,
            VAR_INT, ClientClickWindowPacket::stateId,
            SHORT, ClientClickWindowPacket::slot,
            BYTE, ClientClickWindowPacket::button,
            Enum(ClickType.class), ClientClickWindowPacket::clickType,
            SHORT.mapValue(ItemStack.Hash.NETWORK_TYPE, MAX_CHANGED_SLOTS), ClientClickWindowPacket::changedSlots,
            ItemStack.Hash.NETWORK_TYPE, ClientClickWindowPacket::clickedItem,
            ClientClickWindowPacket::new);

    public ClientClickWindowPacket {
        changedSlots = Map.copyOf(changedSlots);
    }

    public enum ClickType {
        PICKUP,
        QUICK_MOVE,
        SWAP,
        CLONE,
        THROW,
        QUICK_CRAFT,
        PICKUP_ALL
    }
}
