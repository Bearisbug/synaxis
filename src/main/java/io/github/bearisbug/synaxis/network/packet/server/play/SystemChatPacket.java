package io.github.bearisbug.synaxis.network.packet.server.play;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import java.util.List;
import java.util.function.UnaryOperator;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.COMPONENT;

public record SystemChatPacket(Component message,
                               boolean overlay) implements ServerPacket.Play, ServerPacket.ComponentHolding {
    public static final NetworkBuffer.Type<SystemChatPacket> SERIALIZER = NetworkBufferTemplate.template(
            COMPONENT, SystemChatPacket::message,
            BOOLEAN, SystemChatPacket::overlay,
            SystemChatPacket::new);

    @Override
    public List<Component> components() {
        return List.of(message);
    }

    @Override
    public ServerPacket copyWithOperator(UnaryOperator<Component> operator) {
        return new SystemChatPacket(operator.apply(message), overlay);
    }
}
