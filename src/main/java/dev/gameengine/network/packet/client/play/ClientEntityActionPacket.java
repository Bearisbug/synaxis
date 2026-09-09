package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.Enum;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record ClientEntityActionPacket(int playerId, Action action,
                                       int horseJumpBoost) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientEntityActionPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientEntityActionPacket::playerId,
            Enum(Action.class), ClientEntityActionPacket::action,
            VAR_INT, ClientEntityActionPacket::horseJumpBoost,
            ClientEntityActionPacket::new);

    public enum Action {
        LEAVE_BED,
        START_SPRINTING,
        STOP_SPRINTING,
        START_JUMP_HORSE,
        STOP_JUMP_HORSE,
        OPEN_HORSE_INVENTORY,
        START_FLYING_ELYTRA
    }
}
