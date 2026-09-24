package io.github.bearisbug.synaxis.entity.player;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertNull;

@EnvTest
public class PlayerRemoveIntegrationTest {

    @Test
    public void removingAnOnlinePlayerCallsTheDisconnectEventOnce(Env env) {
        var instance = env.createFlatInstance();
        // The real connection is used because Player#remove kicks the player, and the disconnect
        // handling is where the event used to be called a second time.
        final var connection = new SilentConnection(env.process());
        connection.setClientState(ConnectionState.PLAY);
        connection.setServerState(ConnectionState.PLAY);
        final Player player = env.process().connection()
                .createPlayer(connection, new GameProfile(UUID.randomUUID(), "Leaver"));
        player.setInstance(instance, new Pos(0, 40, 0)).join();

        var disconnects = env.trackEvent(PlayerDisconnectEvent.class, EventFilter.PLAYER, player);
        player.remove();

        disconnects.assertCount(1);
    }

    @Test
    public void aSpawnedPlayerWhoseConnectionDropsIsReportedOnce(Env env) {
        final var connection = new SilentConnection(env.process());
        connection.setClientState(ConnectionState.PLAY);
        connection.setServerState(ConnectionState.PLAY);
        final Player player = env.process().connection()
                .createPlayer(connection, new GameProfile(UUID.randomUUID(), "Settled"));
        player.setInstance(env.createFlatInstance(), new Pos(0, 40, 0)).join();
        final var disconnects = env.trackEvent(PlayerDisconnectEvent.class, EventFilter.PLAYER, player);

        connection.disconnect();
        env.tick();
        env.tick();

        disconnects.assertCount(1);
    }

    @Test
    public void aPlayerThatLeavesAfterAcknowledgingTheConfigurationButBeforeItSpawnsIsReportedOnce(Env env) {
        leavesBeforeItSpawns(env, true);
    }

    @Test
    public void aPlayerThatLeavesBeforeAcknowledgingTheConfigurationIsReportedOnce(Env env) {
        leavesBeforeItSpawns(env, false);
    }

    /**
     * The server state is play once the server wrote FinishConfigurationPacket, but the player only
     * spawns on a later tick, and nothing ticks an entity outside an instance: a disconnect in
     * between must not wait for an entity tick to report the player gone.
     */
    private static void leavesBeforeItSpawns(Env env, boolean acknowledged) {
        final Instance instance = env.createFlatInstance();
        final var connection = new SilentConnection(env.process());
        connection.setServerState(ConnectionState.CONFIGURATION);
        final Player player = env.process().connection()
                .createPlayer(connection, new GameProfile(UUID.randomUUID(), "Hasty"));
        player.eventNode().addListener(AsyncPlayerConfigurationEvent.class, event -> event.setSpawningInstance(instance));
        CompletableFuture.runAsync(() -> env.process().connection().doConfiguration(player, false),
                Thread.ofVirtual()::start).join();
        // What writing FinishConfigurationPacket does to the server state
        connection.setServerState(ConnectionState.PLAY);
        if (acknowledged) env.process().connection().transitionConfigToPlay(player);
        final var disconnects = env.trackEvent(PlayerDisconnectEvent.class, EventFilter.PLAYER, player);

        connection.disconnect();
        env.tick();
        env.tick();

        disconnects.assertCount(1);
        assertNull(player.getInstance(), "a player that left never spawns");
    }

    private static final class SilentConnection extends PlayerConnection {
        private SilentConnection(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("localhost", 25565);
        }
    }
}
