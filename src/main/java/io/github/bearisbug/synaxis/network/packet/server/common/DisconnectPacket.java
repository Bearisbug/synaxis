package io.github.bearisbug.synaxis.network.packet.server.common;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import java.util.List;
import java.util.function.UnaryOperator;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.COMPONENT;

public record DisconnectPacket(Component message) implements ServerPacket.Configuration, ServerPacket.Play,
        ServerPacket.ComponentHolding {
    public static final NetworkBuffer.Type<DisconnectPacket> SERIALIZER = NetworkBufferTemplate.template(
            COMPONENT, DisconnectPacket::message, DisconnectPacket::new);

    @Override
    public List<Component> components() {
        return List.of(message);
    }

    @Override
    public ServerPacket copyWithOperator(UnaryOperator<Component> operator) {
        return new DisconnectPacket(operator.apply(message));
    }
}
