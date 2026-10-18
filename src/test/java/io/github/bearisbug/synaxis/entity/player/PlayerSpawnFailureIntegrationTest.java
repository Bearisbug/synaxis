package io.github.bearisbug.synaxis.entity.player;

import net.kyori.adventure.text.TranslatableComponent;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.command.builder.Command;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.ChunkLoader;
import io.github.bearisbug.synaxis.network.ConnectionManager;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerInfoRemovePacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A first spawn that fails before the player is in any instance: the client is told, and the leave
 * of the player is reported exactly once, like the leave of any other player.
 */
@EnvTest
public class PlayerSpawnFailureIntegrationTest {

    @Test
    public void aSpawnThatThrowsKicksThePlayerAndReportsItsLeaveOnce(Env env) {
        final Instance instance = env.createFlatInstance();
        final var watcher = env.createConnection();
        watcher.connect(instance, new Pos(0, 42, 0));
        final var removals = watcher.trackIncoming(PlayerInfoRemovePacket.class);
        // Sending the command tree is the last thing the spawn does before the instance, and it asks the conditions
        final Command command = new Command("broken");
        command.setCondition((sender, _) -> {
            if (sender instanceof Player player && player.getUsername().equals("Thrower"))
                throw new IllegalStateException("a broken condition");
            return true;
        });
        env.process().command().register(command);
        final List<Throwable> reported = new CopyOnWriteArrayList<>();
        env.process().exception().setExceptionHandler(reported::add);
        final RecordingConnection connection = new RecordingConnection(env.process());
        final Player player = configure(env, instance, connection, "Thrower");
        final var disconnects = env.trackEvent(PlayerDisconnectEvent.class, EventFilter.PLAYER, player);

        env.tick();
        env.tick();

        assertEquals(List.of("synaxis:internal-error"), connection.disconnectKeys());
        disconnects.assertCount(1);
        assertNull(player.getInstance());
        // The spawn listed it in the tab list of the others before it failed
        removals.assertSingle(packet -> assertEquals(List.of(player.getUuid()), packet.uuids()));
        assertEquals(List.of(), reported, "the failure stays with the spawn of that player");
    }

    @Test
    public void aSpawnWhoseChunksFailToLoadReportsTheLeaveOnce(Env env) {
        // The failing loader legitimately reports to the exception handler, which would otherwise fail the test
        env.process().exception().setExceptionHandler(_ -> {
        });
        final Instance broken = env.process().instance().createInstanceContainer(new FailingChunkLoader(null, null));
        final RecordingConnection connection = new RecordingConnection(env.process());
        final Player player = configure(env, broken, connection, "Unlucky");
        final var disconnects = env.trackEvent(PlayerDisconnectEvent.class, EventFilter.PLAYER, player);

        env.tick();
        env.tick();

        assertEquals(List.of("synaxis:internal-error"), connection.disconnectKeys());
        disconnects.assertCount(1);
        assertNull(player.getInstance());
    }

    /**
     * The client leaves while the spawn chunks load, and a tick looks at the players that left before
     * the load fails. The test thread is that tick: the tick that started the spawn waits for the
     * chunks on another thread, as a tick inside a test does.
     */
    @Test
    public void aSpawnThatFailsAfterTheClientLeftReportsTheLeaveOnce(Env env) throws Exception {
        env.process().exception().setExceptionHandler(_ -> {
        });
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Instance gated = env.process().instance().createInstanceContainer(new FailingChunkLoader(entered, release));
        final RecordingConnection connection = new RecordingConnection(env.process());
        final Player player = configure(env, gated, connection, "Leaver");
        final List<PlayerDisconnectEvent> disconnects = new CopyOnWriteArrayList<>();
        env.process().eventHandler().addListener(PlayerDisconnectEvent.class, event -> {
            if (event.getPlayer() == player) disconnects.add(event);
        });
        final ConnectionManager manager = env.process().connection();

        final CompletableFuture<Void> spawning = CompletableFuture.runAsync(manager::updateWaitingPlayers,
                Thread.ofVirtual()::start);
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the spawn chunks are loading");
        connection.disconnect();
        manager.updateWaitingPlayers();
        assertEquals(0, disconnects.size(), "the spawn is still under way");
        release.countDown();
        spawning.get(10, TimeUnit.SECONDS);

        assertEquals(1, disconnects.size(), "the leave is reported once the spawn failed");
        assertNull(player.getInstance());
    }

    /**
     * Takes a player through the configuration into the queue of players waiting to spawn, as
     * {@code ConnectionManager} does with a real client, on a connection that disconnects for real.
     */
    private static Player configure(Env env, Instance instance, PlayerConnection connection, String name) {
        connection.setServerState(ConnectionState.CONFIGURATION);
        final Player player = Objects.requireNonNull(env.process().connection()
                .createPlayer(connection, new GameProfile(UUID.randomUUID(), name)));
        player.eventNode().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(new Pos(0, 42, 0));
        });
        CompletableFuture.runAsync(() -> env.process().connection().doConfiguration(player, false),
                Thread.ofVirtual()::start).join();
        // What writing FinishConfigurationPacket does to the server state
        connection.setServerState(ConnectionState.PLAY);
        env.process().connection().transitionConfigToPlay(player);
        return player;
    }

    /** Fails every chunk, after waiting for {@code release} when there is one. */
    private record FailingChunkLoader(@Nullable CountDownLatch entered, @Nullable CountDownLatch release)
            implements ChunkLoader {
        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            if (entered != null) entered.countDown();
            if (release != null) {
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            throw new IllegalStateException("the chunk cannot be loaded");
        }

        @Override
        public void saveChunk(Chunk chunk) {
        }

        @Override
        public boolean supportsParallelLoading() {
            return true;
        }
    }

    /** Sends nothing anywhere, it only keeps the keys of the disconnect packets it was given. */
    private static final class RecordingConnection extends PlayerConnection {
        private final List<String> disconnectKeys = new CopyOnWriteArrayList<>();

        private RecordingConnection(ServerProcess process) {
            super(process);
        }

        List<String> disconnectKeys() {
            return disconnectKeys;
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            if (packet instanceof DisconnectPacket(TranslatableComponent message)) disconnectKeys.add(message.key());
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("localhost", 25565);
        }
    }
}
