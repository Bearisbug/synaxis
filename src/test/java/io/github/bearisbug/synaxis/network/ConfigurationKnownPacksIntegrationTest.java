package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.UpdateEnabledFeaturesPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two halves of the configuration early exit: the thread that waits for a known packs answer
 * the client will never send, and the player the lifecycle sets keep after the client is gone.
 */
@EnvTest
public class ConfigurationKnownPacksIntegrationTest {

    @Test
    public void configurationStopsWaitingForKnownPacksWhenTheClientLeaves(Env env) throws InterruptedException {
        final var instance = env.createFlatInstance();
        final var connection = new LeavingConnection(env.process());
        connection.setClientState(ConnectionState.CONFIGURATION);
        connection.setServerState(ConnectionState.CONFIGURATION);
        final Player player = env.process().connection()
                .createPlayer(connection, new GameProfile(UUID.randomUUID(), "Packless"));
        env.process().eventHandler().addListener(AsyncPlayerConfigurationEvent.class,
                event -> event.setSpawningInstance(instance));

        final CountDownLatch configured = new CountDownLatch(1);
        Thread.startVirtualThread(() -> {
            env.process().connection().doConfiguration(player, true);
            configured.countDown();
        });

        assertTrue(configured.await(5, TimeUnit.SECONDS),
                "the configuration thread still waits for a known packs answer, leaking itself and the player");
        assertTrue(env.process().connection().getConfigPlayers().isEmpty(),
                "the player who left is still in the configuration set");
    }

    @Test
    public void aClientGoneBeforeConfigurationStartsLeavesNothingBehind(Env env) {
        final var instance = env.createFlatInstance();
        final var connection = new LeavingConnection(env.process());
        connection.setClientState(ConnectionState.CONFIGURATION);
        connection.setServerState(ConnectionState.CONFIGURATION);
        final Player player = env.process().connection()
                .createPlayer(connection, new GameProfile(UUID.randomUUID(), "Gone"));
        env.process().eventHandler().addListener(AsyncPlayerConfigurationEvent.class,
                event -> event.setSpawningInstance(instance));

        // The socket dies between the login acknowledgement and the first instruction of the
        // configuration thread, so the removal runs before the player is ever tracked.
        connection.disconnect();
        env.process().connection().doConfiguration(player, true);

        assertTrue(env.process().connection().getConfigPlayers().isEmpty(),
                "the configuration set kept a player whose connection was already dropped");
    }

    /**
     * Drops itself the moment the configuration thread is past its {@code isOnline} guard and on
     * its way into the known packs wait: {@link UpdateEnabledFeaturesPacket} is the first packet
     * written after that guard.
     */
    private static final class LeavingConnection extends PlayerConnection {
        private LeavingConnection(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            if (packet instanceof UpdateEnabledFeaturesPacket) disconnect();
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("localhost", 25565);
        }
    }
}
