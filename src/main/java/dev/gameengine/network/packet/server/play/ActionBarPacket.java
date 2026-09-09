package dev.gameengine.network.packet.server.play;

import net.kyori.adventure.text.Component;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import java.util.List;
import java.util.function.UnaryOperator;

import static dev.gameengine.network.NetworkBuffer.COMPONENT;

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
