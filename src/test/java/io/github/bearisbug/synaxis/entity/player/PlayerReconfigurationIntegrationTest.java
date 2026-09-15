package io.github.bearisbug.synaxis.entity.player;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.server.play.ChangeGameStatePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerAbilitiesPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

@EnvTest
public class PlayerReconfigurationIntegrationTest {

    @Test
    public void playPacketsAreNotSentAfterLeavingPlay(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        Player player = connection.connect(instance, new Pos(0, 40, 0));

        player.startConfigurationPhase();
        env.process().connection().updateWaitingPlayers();
        assertFalse(player.isActive(), "a player sent back to configuration is not spawned in any instance");

        // The connection is about to be reconfigured, play packets written to it are a protocol
        // error and get the player disconnected by the write thread.
        var gameStates = connection.trackIncoming(ChangeGameStatePacket.class);
        var abilities = connection.trackIncoming(PlayerAbilitiesPacket.class);
        player.setGameMode(GameMode.CREATIVE);

        gameStates.assertCount(0);
        abilities.assertCount(0);
    }
}
