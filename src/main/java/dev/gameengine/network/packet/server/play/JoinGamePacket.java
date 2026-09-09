package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;
import dev.gameengine.network.packet.server.play.data.PlayerSpawnInfo;

import java.util.List;

import static dev.gameengine.network.NetworkBuffer.BOOLEAN;
import static dev.gameengine.network.NetworkBuffer.INT;
import static dev.gameengine.network.NetworkBuffer.STRING;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record JoinGamePacket(
        int entityId, boolean isHardcore, List<String> worlds, int maxPlayers,
        int viewDistance, int simulationDistance, boolean reducedDebugInfo, boolean enableRespawnScreen,
        boolean doLimitedCrafting, PlayerSpawnInfo playerSpawnInfo, boolean onlineMode, boolean enforcesSecureChat
) implements ServerPacket.Play {
    public static final int MAX_WORLDS = Short.MAX_VALUE;
    public static final NetworkBuffer.Type<JoinGamePacket> SERIALIZER = NetworkBufferTemplate.template(
            INT, JoinGamePacket::entityId,
            BOOLEAN, JoinGamePacket::isHardcore,
            STRING.list(MAX_WORLDS), JoinGamePacket::worlds,
            VAR_INT, JoinGamePacket::maxPlayers,
            VAR_INT, JoinGamePacket::viewDistance,
            VAR_INT, JoinGamePacket::simulationDistance,
            BOOLEAN, JoinGamePacket::reducedDebugInfo,
            BOOLEAN, JoinGamePacket::enableRespawnScreen,
            BOOLEAN, JoinGamePacket::doLimitedCrafting,
            PlayerSpawnInfo.NETWORK_TYPE, JoinGamePacket::playerSpawnInfo,
            BOOLEAN, JoinGamePacket::onlineMode,
            BOOLEAN, JoinGamePacket::enforcesSecureChat,
            JoinGamePacket::new
    );

    public JoinGamePacket {
        worlds = List.copyOf(worlds);
    }
}
