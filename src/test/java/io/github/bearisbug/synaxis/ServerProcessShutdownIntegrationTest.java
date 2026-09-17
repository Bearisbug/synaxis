package io.github.bearisbug.synaxis;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.instance.Chunk;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    @Timeout(120)
    public void stopReturnsWhenTheLastTickNeverFinishes(Env env) throws InterruptedException {
        // The last tick runs arbitrary user code, so it can wedge (blocking listener, infinite loop).
        // Every other wait of the shutdown is bounded, this one must be too or Ctrl+C never returns.
        var process = env.process();
        Chunk chunk = env.createFlatInstance().loadChunk(0, 0).join();
        CountDownLatch ticking = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Entity wedged = new Entity(EntityType.ZOMBIE) {
            @Override
            public void tick(long time) {
                ticking.countDown();
                try {
                    release.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        process.dispatcher().updateElement(wedged, chunk);

        CountDownLatch returned = new CountDownLatch(1);
        Thread stopper = new Thread(() -> {
            process.stop();
            returned.countDown();
        }, "test-shutdown");
        stopper.setDaemon(true);
        try {
            stopper.start();
            assertTrue(ticking.await(30, TimeUnit.SECONDS), "the shutdown never reached the last tick");
            assertTrue(returned.await(60, TimeUnit.SECONDS), "stop() never returned while an element tick was stuck");
        } finally {
            release.countDown();
            stopper.join(TimeUnit.SECONDS.toMillis(30));
        }
    }

    /**
     * The testing fixture stubs {@link PlayerConnection#disconnect()} out, so this test drives the
     * login of a connection that runs the real one.
     */
    private static Player connect(ServerProcess process, Instance instance) {
        final SilentConnection connection = new SilentConnection(process);
        final Player player = process.connection().createPlayer(connection, new GameProfile(UUID.randomUUID(), "Stopped"));
        assertNotNull(player);
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
