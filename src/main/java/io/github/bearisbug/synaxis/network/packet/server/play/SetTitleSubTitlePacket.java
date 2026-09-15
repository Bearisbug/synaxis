package io.github.bearisbug.synaxis.network.packet.server.play;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import java.util.List;
import java.util.function.UnaryOperator;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.COMPONENT;

public record SetTitleSubTitlePacket(
        Component subtitle) implements ServerPacket.Play, ServerPacket.ComponentHolding {
    public static final NetworkBuffer.Type<SetTitleSubTitlePacket> SERIALIZER = NetworkBufferTemplate.template(
            COMPONENT, SetTitleSubTitlePacket::subtitle,
            SetTitleSubTitlePacket::new);

    @Override
    public List<Component> components() {
        return List.of(this.subtitle);
    }

    @Override
    public ServerPacket copyWithOperator(UnaryOperator<Component> operator) {
        return new SetTitleSubTitlePacket(operator.apply(this.subtitle));
    }
}
