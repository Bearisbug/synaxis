package io.github.bearisbug.synaxis.network.packet.server.play;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import java.util.List;
import java.util.function.UnaryOperator;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.COMPONENT;

public record SetTitleTextPacket(Component title) implements ServerPacket.Play, ServerPacket.ComponentHolding {
    public static final NetworkBuffer.Type<SetTitleTextPacket> SERIALIZER = NetworkBufferTemplate.template(
            COMPONENT, SetTitleTextPacket::title,
            SetTitleTextPacket::new);

    @Override
    public List<Component> components() {
        return List.of(this.title);
    }

    @Override
    public ServerPacket copyWithOperator(UnaryOperator<Component> operator) {
        return new SetTitleTextPacket(operator.apply(this.title));
    }
}
