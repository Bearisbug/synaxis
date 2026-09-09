package dev.gameengine.network.packet.server.play;

import net.kyori.adventure.text.Component;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import java.util.List;
import java.util.function.UnaryOperator;

import static dev.gameengine.network.NetworkBuffer.COMPONENT;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record OpenWindowPacket(int windowId, int windowType,
                               Component title) implements ServerPacket.Play, ServerPacket.ComponentHolding {
    public static final NetworkBuffer.Type<OpenWindowPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, OpenWindowPacket::windowId,
            VAR_INT, OpenWindowPacket::windowType,
            COMPONENT, OpenWindowPacket::title,
            OpenWindowPacket::new);

    @Override
    public List<Component> components() {
        return List.of(this.title);
    }

    @Override
    public ServerPacket copyWithOperator(UnaryOperator<Component> operator) {
        return new OpenWindowPacket(this.windowId, this.windowType, operator.apply(this.title));
    }
}
