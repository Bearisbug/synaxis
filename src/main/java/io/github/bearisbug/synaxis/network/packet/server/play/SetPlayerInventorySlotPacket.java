package io.github.bearisbug.synaxis.network.packet.server.play;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import java.util.Collection;
import java.util.function.UnaryOperator;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record SetPlayerInventorySlotPacket(int slot, ItemStack itemStack) implements ServerPacket.Play, ServerPacket.ComponentHolding {
    public static final NetworkBuffer.Type<SetPlayerInventorySlotPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, SetPlayerInventorySlotPacket::slot,
            ItemStack.NETWORK_TYPE, SetPlayerInventorySlotPacket::itemStack,
            SetPlayerInventorySlotPacket::new);

    @Override
    @SuppressWarnings("PreferredInterfaceType") // wider type kept for binary compatibility until the next breaking release
    public Collection<Component> components() {
        return ItemStack.textComponents(itemStack);
    }

    @Override
    public ServerPacket copyWithOperator(UnaryOperator<Component> operator) {
        return new SetPlayerInventorySlotPacket(slot, ItemStack.copyWithOperator(itemStack, operator));
    }
}
