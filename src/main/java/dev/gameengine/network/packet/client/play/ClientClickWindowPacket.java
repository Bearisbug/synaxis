package dev.gameengine.network.packet.client.play;

import dev.gameengine.item.ItemStack;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import java.util.Map;

import static dev.gameengine.network.NetworkBuffer.BYTE;
import static dev.gameengine.network.NetworkBuffer.Enum;
import static dev.gameengine.network.NetworkBuffer.SHORT;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

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
