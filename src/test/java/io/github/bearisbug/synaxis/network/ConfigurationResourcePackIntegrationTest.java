package io.github.bearisbug.synaxis.network;

import net.kyori.adventure.resource.ResourcePackInfo;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static net.kyori.adventure.resource.ResourcePackRequest.resourcePackRequest;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class ConfigurationResourcePackIntegrationTest {
    private static final ResourcePackInfo INFO = ResourcePackInfo.resourcePackInfo(
            UUID.randomUUID(), URI.create("http://localhost:8080/missing.zip"), "i am not a hash!");

    @Test
    public void configurationStopsWaitingForResourcePacksWhenTheClientLeaves(Env env) throws InterruptedException {
        final var instance = env.createFlatInstance();
        final var connection = new SilentConnection(env.process());
        connection.setClientState(ConnectionState.CONFIGURATION);
        connection.setServerState(ConnectionState.CONFIGURATION);
        final Player player = env.process().connection()
                .createPlayer(connection, new GameProfile(UUID.randomUUID(), "Packless"));
        env.process().eventHandler().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().sendResourcePacks(resourcePackRequest().packs(INFO).build());
        });

        final CountDownLatch configured = new CountDownLatch(1);
        Thread.startVirtualThread(() -> {
            env.process().connection().doConfiguration(player, false);
            configured.countDown();
        });
        assertTrue(awaitPushedPack(player), "the resource pack was never pushed to the client");

        // The client closes the socket on the resource pack prompt without answering
        connection.disconnect();

        assertTrue(configured.await(5, TimeUnit.SECONDS),
                "the configuration thread still waits for an answer, leaking itself and the player");
    }

    private static boolean awaitPushedPack(Player player) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            if (player.getResourcePackFuture() != null) return true;
            Thread.sleep(10);
        }
        return false;
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
