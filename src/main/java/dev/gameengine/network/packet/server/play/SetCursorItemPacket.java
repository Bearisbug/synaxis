package dev.gameengine.network.packet.server.play;

import net.kyori.adventure.text.Component;
import dev.gameengine.item.ItemStack;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import java.util.Collection;
import java.util.function.UnaryOperator;

public record SetCursorItemPacket(ItemStack itemStack) implements ServerPacket.Play, ServerPacket.ComponentHolding {
    public static final NetworkBuffer.Type<SetCursorItemPacket> SERIALIZER = NetworkBufferTemplate.template(
            ItemStack.NETWORK_TYPE, SetCursorItemPacket::itemStack,
            SetCursorItemPacket::new);

    @Override
    @SuppressWarnings("PreferredInterfaceType") // wider type kept for binary compatibility until the next breaking release
    public Collection<Component> components() {
        return ItemStack.textComponents(itemStack);
    }

    @Override
    public ServerPacket copyWithOperator(UnaryOperator<Component> operator) {
        return new SetCursorItemPacket(ItemStack.copyWithOperator(itemStack, operator));
    }
}
