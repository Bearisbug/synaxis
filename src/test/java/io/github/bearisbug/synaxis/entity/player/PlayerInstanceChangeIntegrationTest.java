package io.github.bearisbug.synaxis.entity.player;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.player.PlayerChunkUnloadEvent;
import io.github.bearisbug.synaxis.event.player.PlayerSpawnEvent;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.ChunkLoader;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.RespawnPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.thread.TickThread;
import io.github.bearisbug.synaxis.world.DimensionType;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class PlayerInstanceChangeIntegrationTest {

    /** Holds every chunk load back until the gate opens, then lets the generator fill the chunk. */
    private record GatedChunkLoader(CountDownLatch gate) implements ChunkLoader {
        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            try {
                gate.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return null;
        }

        @Override
        public void saveChunk(Chunk chunk) {
        }

        @Override
        public boolean supportsParallelLoading() {
            return true;
        }
    }

    /** Loads the chunk at the origin at once and holds every other chunk back until the gate opens. */
    private record OriginFirstChunkLoader(CountDownLatch gate) implements ChunkLoader {
        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            if (chunkX == 0 && chunkZ == 0) return null;
            return new GatedChunkLoader(gate).loadChunk(instance, chunkX, chunkZ);
        }

        @Override
        public void saveChunk(Chunk chunk) {
        }

        @Override
        public boolean supportsParallelLoading() {
            return true;
        }
    }

    private static Instance gated(Env env, CountDownLatch gate) {
        return env.createFlatInstance(new GatedChunkLoader(gate));
    }

    /**
     * Joined on the tick thread of the player, the future of an instance change waiting for its chunks
     * runs that change there, and nothing else: the tasks queued for the tick scheduler thread stay
     * on it. It used to process the whole scheduler of the process on the tick thread.
     */
    @Test
    public void aJoinOnTheTickThreadRunsOnlyItsOwnContinuation(Env env) throws InterruptedException {
        var origin = env.createFlatInstance();
        var gate = new CountDownLatch(1);
        var target = gated(env, gate);
        Player player = env.createPlayer(origin, new Pos(0, 40, 0));
        env.tick(); // The player gets its tick thread
        var spawns = env.trackEvent(PlayerSpawnEvent.class, EventFilter.PLAYER, player);

        final AtomicReference<Thread> joinedOn = new AtomicReference<>();
        final AtomicReference<Thread> processedOn = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch joined = new CountDownLatch(1);
        player.scheduler().scheduleNextTick(() -> {
            try {
                env.process().scheduler().scheduleNextProcess(() -> processedOn.set(Thread.currentThread()));
                final CompletableFuture<Void> change = player.setInstance(target, new Pos(0, 40, 0));
                assertFalse(change.isDone(), "the target chunks were not held back");
                Thread.ofPlatform().daemon().start(gate::countDown);
                change.join();
                joinedOn.set(Thread.currentThread());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                joined.countDown();
            }
        });
        env.tick();
        assertTrue(joined.await(10, TimeUnit.SECONDS), "the join did not return");
        assertNull(failure.get(), () -> "the change failed: " + failure.get());
        assertInstanceOf(TickThread.class, joinedOn.get(), "the join was meant to happen on the tick thread");

        env.tick(); // The tick scheduler thread processes what was queued for it
        assertNotNull(processedOn.get(), "the task queued for the next process never ran");
        assertFalse(processedOn.get() instanceof TickThread,
                "the join ran a task of the scheduler of the process on " + processedOn.get().getName());
        assertSame(target, player.getInstance());
        spawns.assertCount(1);
    }

    @Test
    public void aRemovedPlayerGivesUpItsInstanceChange(Env env) {
        var origin = env.createFlatInstance();
        var gate = new CountDownLatch(1);
        var target = gated(env, gate);
        Player player = env.createPlayer(origin, new Pos(0, 40, 0));
        var spawns = env.trackEvent(PlayerSpawnEvent.class, EventFilter.PLAYER, player);

        final CompletableFuture<Void> change = player.setInstance(target, new Pos(0, 40, 0));
        assertFalse(change.isDone());
        player.remove();
        gate.countDown();

        assertThrows(CancellationException.class, change::join);
        assertTrue(change.isCancelled());
        spawns.assertEmpty();
        assertEquals(0, target.chunkTicketCount(0, 0), "the removed player left view tickets behind");
        assertFalse(target.getPlayers().contains(player));
    }

    /**
     * A listener of a chunk the change unloads from the old ring removes the player, once the change is admitted:
     * the change is given up before the player is placed in the new instance.
     */
    @Test
    public void aPlayerRemovedByAnUnloadOfItsChangeGivesItUp(Env env) {
        var origin = env.createFlatInstance();
        var target = env.createFlatInstance();
        Player player = env.createPlayer(origin, new Pos(0, 40, 0));
        var spawns = env.trackEvent(PlayerSpawnEvent.class, EventFilter.PLAYER, player);
        final AtomicBoolean removed = new AtomicBoolean();
        env.process().eventHandler().addListener(PlayerChunkUnloadEvent.class, event -> {
            if (event.getPlayer() == player && removed.compareAndSet(false, true)) player.remove();
        });

        final CompletableFuture<Void> change = player.setInstance(target, new Pos(0, 40, 0));

        assertThrows(CancellationException.class, change::join);
        assertTrue(removed.get(), "the change unloaded no chunk of the old ring");
        spawns.assertEmpty();
        assertEquals(0, target.chunkTicketCount(0, 0), "the removed player left view tickets behind");
        assertFalse(target.getPlayers().contains(player));
    }

    @Test
    public void aLaterInstanceChangeSupersedesAPendingOne(Env env) {
        var origin = env.createFlatInstance();
        var gate = new CountDownLatch(1);
        var abandoned = gated(env, gate);
        var target = env.createFlatInstance();
        Player player = env.createPlayer(origin, new Pos(0, 40, 0));
        var spawns = env.trackEvent(PlayerSpawnEvent.class, EventFilter.PLAYER, player);

        final CompletableFuture<Void> first = player.setInstance(abandoned, new Pos(0, 40, 0));
        player.setInstance(target, new Pos(0, 40, 0)).join();
        assertSame(target, player.getInstance());
        gate.countDown();

        assertThrows(CancellationException.class, first::join);
        assertSame(target, player.getInstance(), "the superseded change still moved the player");
        spawns.assertCount(1);
        assertEquals(0, abandoned.chunkTicketCount(0, 0));
    }

    /**
     * Both changes are decided against the instance the player is in: when the first one, to another
     * dimension, was superseded by one that stays in the dimension, no respawn packet may reach the client.
     */
    @Test
    public void aSupersededChangeOfDimensionSendsNoRespawn(Env env) {
        var origin = env.createFlatInstance();
        var netherGate = new CountDownLatch(1);
        var nether = env.process().instance().createInstanceContainer(DimensionType.THE_NETHER, new GatedChunkLoader(netherGate));
        nether.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
        var targetGate = new CountDownLatch(1);
        var target = gated(env, targetGate);
        var connection = env.createConnection();
        Player player = connection.connect(origin, new Pos(0, 40, 0));
        var respawns = connection.trackIncoming(RespawnPacket.class);

        final CompletableFuture<Void> toNether = player.setInstance(nether, new Pos(0, 40, 0));
        final CompletableFuture<Void> toTarget = player.setInstance(target, new Pos(0, 40, 0));
        try {
            netherGate.countDown();
            assertThrows(CancellationException.class, toNether::join);
        } finally {
            targetGate.countDown();
        }
        toTarget.join();

        assertSame(target, player.getInstance());
        respawns.assertEmpty();
    }

    /**
     * A first spawn still loading its chunks when the player leaves the play state is given up, not
     * failed: the player is not kicked, and the join of the test mode does not throw.
     */
    @Test
    public void aFirstSpawnGivenUpIsNotAFailure(Env env) throws Exception {
        var gate = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        final ChunkLoader gated = new GatedChunkLoader(gate);
        var spawnInstance = env.createFlatInstance(new ChunkLoader() {
            @Override
            public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
                entered.countDown();
                return gated.loadChunk(instance, chunkX, chunkZ);
            }

            @Override
            public void saveChunk(Chunk chunk) {
            }

            @Override
            public boolean supportsParallelLoading() {
                return true;
            }
        });
        var connection = env.createConnection(new GameProfile(UUID.randomUUID(), "Leaving"));
        var disconnects = connection.trackIncoming(DisconnectPacket.class);
        final CompletableFuture<Player> connected = new CompletableFuture<>();
        Thread.ofPlatform().daemon().start(() -> {
            try {
                connected.complete(connection.connect(spawnInstance, new Pos(0, 40, 0)));
            } catch (Throwable t) {
                connected.completeExceptionally(t);
            }
        });
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the spawn never asked for its chunks");
        final Player player = env.process().connection().getOnlinePlayerByUsername("Leaving");
        assertNotNull(player);

        // What a reconfiguration does before it queues StartConfiguration
        player.remove(false);
        gate.countDown();

        assertSame(player, connected.get(10, TimeUnit.SECONDS));
        assertNull(player.getInstance(), "the spawn given up still put the player in its instance");
        assertTrue(player.isOnline(), "a spawn given up must not get the player kicked");
        disconnects.assertEmpty();
    }

    @Test
    public void anUnregisteredInstanceIsRefusedBeforeAnyPacket(Env env) {
        var origin = env.createFlatInstance();
        var target = env.createFlatInstance();
        env.destroyInstance(target);
        var connection = env.createConnection();
        Player player = connection.connect(origin, new Pos(0, 40, 0));
        var packets = connection.trackIncoming();

        assertThrows(IllegalStateException.class, () -> player.setInstance(target, new Pos(0, 40, 0)));
        packets.assertEmpty();
        assertSame(origin, player.getInstance());
    }

    @Test
    public void theInstanceThePlayerIsInIsRefusedWithATeleportHint(Env env) {
        var origin = env.createFlatInstance();
        Player player = env.createPlayer(origin, new Pos(0, 40, 0));

        final var error = assertThrows(IllegalArgumentException.class, () -> player.setInstance(origin, new Pos(5, 40, 5)));
        assertTrue(error.getMessage().contains(player.getUsername()), error.getMessage());
        assertTrue(error.getMessage().contains("teleport"), error.getMessage());
    }

    /**
     * The chunk ring a player is about to spawn in holds view tickets while it loads: the spawn chunk, loaded long before
     * the rest of the ring, is not unloaded for its grace period meanwhile. Once the player is in, every chunk of its ring
     * has one ticket, its own.
     */
    @Test
    public void theRingOfAPendingSpawnIsNotUnloadedWhileItLoads(Env env) {
        var origin = env.createFlatInstance();
        var gate = new CountDownLatch(1);
        var target = env.createFlatInstance(new OriginFirstChunkLoader(gate));
        target.setChunkUnloadGraceTicks(1);
        Player player = env.createPlayer(origin, new Pos(0, 40, 0));

        final CompletableFuture<Void> change = player.setInstance(target, new Pos(0.5, 40, 0.5));
        assertFalse(change.isDone(), "the ring was not held back");
        target.loadChunk(0, 0).join();
        for (int i = 0; i < 5; i++) env.tick();
        assertNotNull(target.getChunk(0, 0), "the spawn chunk was unloaded while the rest of the ring loaded");

        gate.countDown();
        change.join();
        assertSame(target, player.getInstance());
        final int radius = player.effectiveViewDistance();
        assertEquals(1, target.chunkTicketCount(0, 0), "the spawned player holds one ticket on its chunk");
        assertEquals(1, target.chunkTicketCount(radius, -radius), "and one on each chunk of its ring");
    }
}
