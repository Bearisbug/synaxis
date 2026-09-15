package io.github.bearisbug.synaxis.network.packet.server.login;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import java.util.List;
import java.util.function.UnaryOperator;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.JSON_COMPONENT;

public record LoginDisconnectPacket(Component kickMessage) implements ServerPacket.Login,
        ServerPacket.ComponentHolding {
    public static final NetworkBuffer.Type<LoginDisconnectPacket> SERIALIZER = NetworkBufferTemplate.template(
            JSON_COMPONENT, LoginDisconnectPacket::kickMessage,
            LoginDisconnectPacket::new);

    @Override
    public List<Component> components() {
        return List.of(this.kickMessage);
    }

    @Override
    public LoginDisconnectPacket copyWithOperator(UnaryOperator<Component> operator) {
        return new LoginDisconnectPacket(operator.apply(this.kickMessage));
    }
}
