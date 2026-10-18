package io.github.bearisbug.synaxis.network;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.listener.preplay.LoginListener;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginAcknowledgedPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginDisconnectPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerSocketConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline mode derives the profile uuid from the username, so two clients picking the same name
 * reach the login with the very same uuid. The second one used to walk all the way into the play
 * state and only then be rejected by the entity tracker, from a continuation that swallowed the
 * failure.
 */
@EnvTest
public class DuplicateUuidLoginIntegrationTest {

    @Test
    public void aSecondLoginOnALiveUuidIsRejected(Env env) throws IOException {
        final GameProfile profile = new GameProfile(UUID.randomUUID(), "soak0000");
        final Instance instance = env.createFlatInstance();
        final List<Throwable> exceptions = captureExceptions(env);

        final Player first = env.createConnection(profile).connect(instance, new Pos(0, 42, 0));

        try (KickCapturingConnection second = new KickCapturingConnection(env.process())) {
            second.UNSAFE_setProfile(profile);
            // Where a real connection is by now: writing LoginSuccess moves the server state to
            // CONFIGURATION (PacketVanilla#nextServerState), and the client acknowledges it after
            // that. So the rejection leaves as a configuration Disconnect, not a LoginDisconnect
            second.setServerState(ConnectionState.CONFIGURATION);
            LoginListener.loginAckListener(new ClientLoginAcknowledgedPacket(), second);

            assertEquals("synaxis:duplicate-login", second.kickKey(),
                    "the second login on a live uuid was not rejected with the duplicate login reason");
            assertInstanceOf(DisconnectPacket.class, second.kickPacket(),
                    "the reason reached the client as something other than the packet documented in DESIGN.md 14");
        }

        assertEquals(List.of(first), List.copyOf(env.process().connection().getOnlinePlayers()),
                "the rejected login changed the set of players in the play state");
        assertEquals(List.of(first), instance.getEntities().stream().filter(Player.class::isInstance).toList(),
                "the instance holds more than the one player that won the uuid");
        assertSame(first, instance.getEntityTracker().getEntityByUuid(profile.uuid()));
        assertEquals(List.of(), exceptions, "the rejected login reported an exception");
    }

    @Test
    public void exactlyOneOfTwoSimultaneousLoginsOnTheSameUuidWins(Env env) {
        final ServerProcess process = env.process();
        for (int attempt = 0; attempt < 64; attempt++) {
            final GameProfile profile = new GameProfile(UUID.randomUUID(), "soak0001");
            final CompletableFuture<Player> left = new CompletableFuture<>();
            final CompletableFuture<Player> right = new CompletableFuture<>();
            Thread.startVirtualThread(() -> claim(process, profile, left));
            Thread.startVirtualThread(() -> claim(process, profile, right));

            final Player won = join(left);
            final Player lost = join(right);
            assertTrue(won == null ^ lost == null,
                    "two connections carrying the same uuid both got a player at attempt " + attempt);
            final Player player = won != null ? won : lost;
            process.connection().removePlayer(player.getPlayerConnection());
        }
    }

    @Test
    public void theUuidIsFreeAgainOnceTheOldConnectionIsDropped(Env env) throws IOException {
        final GameProfile profile = new GameProfile(UUID.randomUUID(), "soak0002");
        final Instance instance = env.createFlatInstance();
        final Player first = env.createConnection(profile).connect(instance, new Pos(0, 42, 0));

        try (KickCapturingConnection retry = new KickCapturingConnection(env.process())) {
            retry.UNSAFE_setProfile(profile);
            retry.setServerState(ConnectionState.CONFIGURATION);
            LoginListener.loginAckListener(new ClientLoginAcknowledgedPacket(), retry);
            assertEquals("synaxis:duplicate-login", retry.kickKey(),
                    "a reconnection is rejected while the previous connection is still tracked");
        }

        // What a client is told to retry after: the old connection going away. The testing
        // fixture stubs PlayerConnection#disconnect out, so this is the part of it that matters
        env.process().connection().removePlayer(first.getPlayerConnection());
        assertNull(env.process().connection().getPlayer(first.getPlayerConnection()));

        final Player second = env.createConnection(profile).connect(instance, new Pos(0, 42, 0));
        assertNotNull(second, "the uuid stayed locked after its connection was dropped");
    }

    private static void claim(ServerProcess process, GameProfile profile, CompletableFuture<Player> result) {
        try (SocketChannel channel = SocketChannel.open()) {
            result.complete(process.connection().createPlayer(
                    new PlayerSocketConnection(process, channel, new InetSocketAddress("localhost", 25565),
                            Thread.currentThread(), Thread.currentThread()),
                    profile));
        } catch (Throwable throwable) {
            result.completeExceptionally(throwable);
        }
    }

    private static Player join(CompletableFuture<Player> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new AssertionError("a login claim never finished", e);
        }
    }

    private static List<Throwable> captureExceptions(Env env) {
        final List<Throwable> exceptions = new CopyOnWriteArrayList<>();
        env.process().exception().setExceptionHandler(exceptions::add);
        return exceptions;
    }

    static final class KickCapturingConnection extends PlayerSocketConnection implements AutoCloseable {
        private final SocketChannel channel;
        private volatile @Nullable SendablePacket kickPacket;

        KickCapturingConnection(ServerProcess process) throws IOException {
            this(process, SocketChannel.open());
        }

        KickCapturingConnection(ServerProcess process, SocketChannel channel) {
            super(process, channel, new InetSocketAddress("localhost", 25565),
                    Thread.currentThread(), Thread.currentThread());
            this.channel = channel;
        }

        SendablePacket kickPacket() {
            final SendablePacket packet = kickPacket;
            assertNotNull(packet, "the connection was never kicked");
            return packet;
        }

        String kickKey() {
            final SendablePacket packet = kickPacket();
            final Component reason = packet instanceof LoginDisconnectPacket login
                    ? login.kickMessage() : assertInstanceOf(DisconnectPacket.class, packet).message();
            return assertInstanceOf(TranslatableComponent.class, reason,
                    "the kick reason is not an error code of the engine").key();
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            if (packet instanceof DisconnectPacket || packet instanceof LoginDisconnectPacket) kickPacket = packet;
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
