package dev.gameengine.network.packet.server.login;

import net.kyori.adventure.text.Component;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import java.util.List;
import java.util.function.UnaryOperator;

import static dev.gameengine.network.NetworkBuffer.JSON_COMPONENT;

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
