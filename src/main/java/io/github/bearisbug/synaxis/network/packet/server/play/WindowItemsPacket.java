package io.github.bearisbug.synaxis.network.packet.server.play;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record WindowItemsPacket(int windowId, int stateId, List<ItemStack> items,
                                ItemStack carriedItem) implements ServerPacket.Play, ServerPacket.ComponentHolding {
    public static final int MAX_ENTRIES = 128;

    public static final NetworkBuffer.Type<WindowItemsPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, WindowItemsPacket::windowId,
            VAR_INT, WindowItemsPacket::stateId,
            ItemStack.NETWORK_TYPE.list(MAX_ENTRIES), WindowItemsPacket::items,
            ItemStack.NETWORK_TYPE, WindowItemsPacket::carriedItem,
            WindowItemsPacket::new);

    public WindowItemsPacket {
        items = List.copyOf(items);
    }

    @Override
    public List<Component> components() {
        final var components = new ArrayList<Component>();
        for (ItemStack item : this.items) components.addAll(ItemStack.textComponents(item));
        components.addAll(ItemStack.textComponents(this.carriedItem));
        return components;
    }

    @Override
    public ServerPacket copyWithOperator(UnaryOperator<Component> operator) {
        return new WindowItemsPacket(
                this.windowId,
                this.stateId,
                this.items.stream().map(item -> ItemStack.copyWithOperator(item, operator)).toList(),
                ItemStack.copyWithOperator(this.carriedItem, operator)
        );
    }
}
