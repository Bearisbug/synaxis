package io.github.bearisbug.synaxis.network.packet.server.play;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import java.util.List;
import java.util.function.UnaryOperator;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.COMPONENT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record DeathCombatEventPacket(int playerId,
                                     Component message) implements ServerPacket.Play, ServerPacket.ComponentHolding {
    public static final NetworkBuffer.Type<DeathCombatEventPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, DeathCombatEventPacket::playerId,
            COMPONENT, DeathCombatEventPacket::message,
            DeathCombatEventPacket::new);

    @Override
    public List<Component> components() {
        return List.of(this.message);
    }

    @Override
    public ServerPacket copyWithOperator(UnaryOperator<Component> operator) {
        return new DeathCombatEventPacket(this.playerId, operator.apply(this.message));
    }
}
