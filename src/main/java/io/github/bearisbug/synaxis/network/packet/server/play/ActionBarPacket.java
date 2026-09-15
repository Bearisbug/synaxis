package io.github.bearisbug.synaxis.network.packet.server.play;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import java.util.List;
import java.util.function.UnaryOperator;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.COMPONENT;

public record ActionBarPacket(Component text) implements ServerPacket.Play, ServerPacket.ComponentHolding {
    public static final NetworkBuffer.Type<ActionBarPacket> SERIALIZER = NetworkBufferTemplate.template(
            COMPONENT, ActionBarPacket::text,
            ActionBarPacket::new);

    @Override
    public List<Component> components() {
        return List.of(this.text);
    }

    @Override
    public ServerPacket copyWithOperator(UnaryOperator<Component> operator) {
        return new ActionBarPacket(operator.apply(this.text));
    }
}
