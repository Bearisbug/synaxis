package io.github.bearisbug.synaxis.entity.player;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.server.play.ChangeGameStatePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerAbilitiesPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    public void aPlayerBackFromConfigurationSpawnsAgainOnItsFormerTickThread(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        Player player = connection.connect(instance, new Pos(0, 40, 0));
        env.tick();
        assertNotNull(player.acquirable().assignedThread(), "the first tick hands the player to a tick thread");

        var connections = env.process().connection();
        player.acquirable().sync(p -> {
            p.startConfigurationPhase();
            connections.updateWaitingPlayers();
        });
        assertFalse(player.isActive(), "a player sent back to configuration is not spawned in any instance");

        // Back to play the way the login got there: the configuration on a virtual thread, then the join.
        // The test connection never answered the known packs query of the login, a client does.
        player.getPlayerConnection().receiveKnownPacksResponse(List.of());
        var configured = new CompletableFuture<Void>();
        Thread.startVirtualThread(() -> {
            try {
                connections.doConfiguration(player, false);
                connections.transitionConfigToPlay(player);
                configured.complete(null);
            } catch (Throwable t) {
                configured.completeExceptionally(t);
            }
        });
        configured.join();
        // Still assigned to the tick thread of its first spawn: the spawn writes its health on that thread's hold
        connections.updateWaitingPlayers();

        assertTrue(player.isOnline(), "the second spawn kept the player");
        assertSame(instance, player.getInstance(), "the player spawned again in the instance of its configuration");
    }
}
