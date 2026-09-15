package io.github.bearisbug.synaxis;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
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
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class ServerProcessShutdownIntegrationTest {

    @Test
    @Timeout(60)
    public void playPlayersAreRemovedOnShutdown(Env env) {
        // Kicking a PLAY player only schedules Entity#remove on its next tick, and that removal is
        // what fires PlayerDisconnectEvent. A shutdown that kicks after the tick threads are gone
        // drops it silently.
        var process = env.process();
        var player = connect(process, env.createFlatInstance());
        AtomicReference<Player> disconnected = new AtomicReference<>();
        process.eventHandler().addListener(PlayerDisconnectEvent.class, event -> disconnected.set(event.getPlayer()));

        process.stop();

        assertSame(player, disconnected.get(), "PlayerDisconnectEvent was never fired for the player");
        assertTrue(player.isRemoved(), "the player was never removed");
    }

    /**
     * The testing fixture stubs {@link PlayerConnection#disconnect()} out, so this test drives the
     * login of a connection that runs the real one.
     */
    private static Player connect(ServerProcess process, Instance instance) {
        final SilentConnection connection = new SilentConnection(process);
        final Player player = process.connection().createPlayer(connection, new GameProfile(UUID.randomUUID(), "Stopped"));
        player.eventNode().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(new Pos(0, 40, 0));
        });
        // The configuration event is asserted to run on a virtual thread
        CompletableFuture.runAsync(() -> {
            process.connection().doConfiguration(player, false);
            process.connection().transitionConfigToPlay(player);
        }, Thread.ofVirtual()::start).join();
        connection.setClientState(ConnectionState.PLAY);
        connection.setServerState(ConnectionState.PLAY);
        process.connection().updateWaitingPlayers();
        return player;
    }

    private static final class SilentConnection extends PlayerConnection {
        private SilentConnection(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            // Nothing to write to
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("localhost", 25565);
        }
    }
}
