package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginSuccessPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerSocketConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@EnvTest
public class ConnectionManagerIntegrationTest {

    private GameProfile[] profiles;

    @BeforeEach
    public void setup(Env env) {
        profiles = new GameProfile[]{
                new GameProfile(UUID.randomUUID(), "Synaxis"),
                new GameProfile(UUID.randomUUID(), "Notch")};
    }

    @Test
    public void testPartialFind(Env env) {
        Instance instance = env.createEmptyInstance();
        Player enginePlayer = env.createConnection(profiles[0]).connect(instance, Pos.ZERO);
        ConnectionManager connectionManager = env.process().connection();

        assertEquals(enginePlayer, connectionManager.findOnlinePlayer("Syn"));
        assertNull(connectionManager.findOnlinePlayer("No"));

        Player notchPlayer = env.createConnection(profiles[1]).connect(instance, Pos.ZERO);

        assertEquals(enginePlayer, connectionManager.findOnlinePlayer("Syn"));
        assertEquals(notchPlayer, connectionManager.findOnlinePlayer("No"));
        assertNull(connectionManager.findOnlinePlayer("leo"));
    }

    @Test
    public void profileIsPublishedBeforeLoginSuccess(Env env) throws IOException {
        final GameProfile profile = profiles[0];

        try (SocketChannel channel = SocketChannel.open()) {
            final var connection = new ProfileCapturingConnection(env.process(), channel);
            connection.setClientState(ConnectionState.LOGIN);

            final CompletableFuture<GameProfile> future = new CompletableFuture<>();
            Thread.startVirtualThread(() -> {
                try {
                    future.complete(env.process().connection().transitionLoginToConfig(connection, profiles[0]));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
            final GameProfile result = future.join();

            assertSame(profile, result);
            assertSame(profile, connection.profileWhenLoginSuccessSent.join());
            assertSame(profile, connection.gameProfile());
        }
    }

    private static final class ProfileCapturingConnection extends PlayerSocketConnection {
        private final CompletableFuture<GameProfile> profileWhenLoginSuccessSent = new CompletableFuture<>();

        private ProfileCapturingConnection(ServerProcess process, SocketChannel channel) {
            super(process, channel, new InetSocketAddress("localhost", 25565),
                    Thread.currentThread(), Thread.currentThread());
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            if (packet instanceof LoginSuccessPacket) {
                // Model the socket reader handling an immediate acknowledgement before the login
                // thread resumes from sendPacket.
                Thread.startVirtualThread(() -> profileWhenLoginSuccessSent.complete(gameProfile()));
                profileWhenLoginSuccessSent.join();
            }
        }
    }

}
