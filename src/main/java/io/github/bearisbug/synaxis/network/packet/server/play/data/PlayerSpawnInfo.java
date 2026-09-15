package io.github.bearisbug.synaxis.network.packet.server.play.data;

import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.LONG;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record PlayerSpawnInfo(int dimensionType, String world, long hashedSeed, GameMode gameMode,
                              @Nullable GameMode previousGameMode, boolean debug, boolean flat,
                              @Nullable WorldPos deathLocation, int portalCooldown, int seaLevel) {
    public static final NetworkBuffer.Type<PlayerSpawnInfo> NETWORK_TYPE = NetworkBufferTemplate.template(
            VAR_INT, PlayerSpawnInfo::dimensionType,
            STRING, PlayerSpawnInfo::world,
            LONG, PlayerSpawnInfo::hashedSeed,
            GameMode.NETWORK_TYPE, PlayerSpawnInfo::gameMode,
            GameMode.OPT_NETWORK_TYPE, PlayerSpawnInfo::previousGameMode,
            BOOLEAN, PlayerSpawnInfo::debug,
            BOOLEAN, PlayerSpawnInfo::flat,
            WorldPos.NETWORK_TYPE.optional(), PlayerSpawnInfo::deathLocation,
            VAR_INT, PlayerSpawnInfo::portalCooldown,
            VAR_INT, PlayerSpawnInfo::seaLevel,
            PlayerSpawnInfo::new
    );

    public PlayerSpawnInfo {
        Objects.requireNonNull(gameMode, "gameMode");
    }
}
