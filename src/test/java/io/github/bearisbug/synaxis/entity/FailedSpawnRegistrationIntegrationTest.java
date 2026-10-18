package io.github.bearisbug.synaxis.entity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A spawn that fails inside the {@code setInstance} continuation used to be swallowed, which left
 * the entity inside the instance while the tracker had never heard of it: nothing could move or
 * remove it again, yet it kept taking packets.
 */
@EnvTest
public class FailedSpawnRegistrationIntegrationTest {

    @Test
    public void aRejectedRegistrationFailsTheSpawnFuture(Env env) {
        final Instance instance = env.createFlatInstance();
        final UUID uuid = UUID.randomUUID();
        final Entity owner = new Entity(EntityType.ZOMBIE, uuid);
        owner.setInstance(instance, new Pos(0, 42, 0)).join();

        final Entity clash = new Entity(EntityType.ZOMBIE, uuid);
        final CompletionException failure = assertThrows(CompletionException.class,
                () -> clash.setInstance(instance, new Pos(0, 42, 0)).join(),
                "the rejected registration completed the spawn future normally");
        assertInstanceOf(IllegalStateException.class, failure.getCause());

        assertSame(owner, instance.getEntityTracker().getEntityByUuid(uuid));
        assertNull(instance.getEntityTracker().getEntityById(clash.getEntityId()),
                "the entity whose registration was rejected is in the tracker");
    }

    @Test
    public void aPlayerWhoseRegistrationFailsIsDisconnected(Env env) {
        final Instance instance = env.createFlatInstance();
        final UUID uuid = UUID.randomUUID();
        // Any registration failure must reach the client, this one is simply the reproducible one
        final Entity squatter = new Entity(EntityType.ZOMBIE, uuid);
        squatter.setInstance(instance, new Pos(0, 42, 0)).join();

        final KickCapturingConnection connection = new KickCapturingConnection(env.process());
        final ServerProcess process = env.process();
        final Player player = process.connection().createPlayer(connection, new GameProfile(uuid, "soak0003"));
        assertNotNull(player);
        // What the plugins and the shard host clean up on: the player is reported gone once removed
        final AtomicInteger disconnects = new AtomicInteger();
        process.eventHandler().addListener(PlayerDisconnectEvent.class, event -> {
            if (event.getPlayer() == player) disconnects.incrementAndGet();
        });
        player.setRespawnPoint(new Pos(0, 42, 0));
        player.setPendingOptions(instance, false);
        process.connection().transitionConfigToPlay(player);
        // Both states are already PLAY when the join is drained, the acknowledgement that queued
        // the player is only accepted in that state
        connection.setClientState(ConnectionState.PLAY);
        connection.setServerState(ConnectionState.PLAY);
        process.connection().updateWaitingPlayers();

        assertEquals("synaxis:internal-error", connection.kickKey(),
                "the player was left in the world instead of being disconnected");
        assertFalse(process.connection().getOnlinePlayers().contains(player),
                "the player whose registration failed is still in the play state");
        assertSame(squatter, instance.getEntityTracker().getEntityByUuid(uuid));
        assertNull(instance.getEntityTracker().getEntityById(player.getEntityId()),
                "the player whose registration failed is in the tracker");

        env.tick();
        assertTrue(player.isRemoved(), "the player whose registration failed stayed in the world");
        assertNull(player.getInstance());
        assertEquals(1, disconnects.get(), "the player whose registration failed was not reported gone once");
    }

    private static final class KickCapturingConnection extends PlayerConnection {
        private volatile @Nullable Component kickReason;
        private volatile boolean online = true;

        private KickCapturingConnection(ServerProcess process) {
            super(process);
        }

        private String kickKey() {
            final Component reason = kickReason;
            assertNotNull(reason, "the connection was never kicked");
            return assertInstanceOf(TranslatableComponent.class, reason,
                    "the kick reason is not an error code of the engine").key();
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            if (packet instanceof DisconnectPacket disconnect) kickReason = disconnect.message();
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("localhost", 25565);
        }

        @Override
        public boolean isOnline() {
            return online;
        }

        @Override
        public void disconnect() {
            online = false;
            super.disconnect();
        }
    }
}
