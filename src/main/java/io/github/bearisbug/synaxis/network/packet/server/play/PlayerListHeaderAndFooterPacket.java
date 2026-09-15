package io.github.bearisbug.synaxis.network.packet.server.play;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import java.util.List;
import java.util.function.UnaryOperator;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.COMPONENT;

public record PlayerListHeaderAndFooterPacket(Component header,
                                              Component footer) implements ServerPacket.Play, ServerPacket.ComponentHolding {
    public static final NetworkBuffer.Type<PlayerListHeaderAndFooterPacket> SERIALIZER = NetworkBufferTemplate.template(
            COMPONENT, PlayerListHeaderAndFooterPacket::header,
            COMPONENT, PlayerListHeaderAndFooterPacket::footer,
            PlayerListHeaderAndFooterPacket::new);

    @Override
    public List<Component> components() {
        return List.of(header, footer);
    }

    @Override
    public ServerPacket copyWithOperator(UnaryOperator<Component> operator) {
        return new PlayerListHeaderAndFooterPacket(operator.apply(header), operator.apply(footer));
    }
}
