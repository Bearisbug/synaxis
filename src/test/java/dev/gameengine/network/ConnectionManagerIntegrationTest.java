package dev.gameengine.network;

import dev.gameengine.coordinate.Pos;
import dev.gameengine.ServerProcess;
import dev.gameengine.entity.Player;
import dev.gameengine.instance.Instance;
import dev.gameengine.network.packet.server.SendablePacket;
import dev.gameengine.network.packet.server.login.LoginSuccessPacket;
import dev.gameengine.network.player.GameProfile;
import dev.gameengine.network.player.PlayerSocketConnection;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
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
                new GameProfile(UUID.randomUUID(), "GameEngine"),
                new GameProfile(UUID.randomUUID(), "Notch")};
    }

    @Test
    public void testPartialFind(Env env) {
        Instance instance = env.createEmptyInstance();
        Player enginePlayer = env.createConnection(profiles[0]).connect(instance, Pos.ZERO);
        ConnectionManager connectionManager = env.process().connection();

        assertEquals(enginePlayer, connectionManager.findOnlinePlayer("Game"));
        assertNull(connectionManager.findOnlinePlayer("No"));

        Player notchPlayer = env.createConnection(profiles[1]).connect(instance, Pos.ZERO);

        assertEquals(enginePlayer, connectionManager.findOnlinePlayer("Game"));
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
