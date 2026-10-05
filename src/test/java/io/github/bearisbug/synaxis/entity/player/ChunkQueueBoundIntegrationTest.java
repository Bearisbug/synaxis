package io.github.bearisbug.synaxis.entity.player;

import io.github.bearisbug.synaxis.coordinate.ChunkRange;
import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.PlayerProvider;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientSettingsPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChunkBatchReceivedPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTeleportConfirmPacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UnloadChunkPacket;
import io.github.bearisbug.synaxis.network.player.ClientSettings;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import it.unimi.dsi.fastutil.longs.LongPriorityQueue;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The chunk send queue of a client that does not acknowledge its batches, or acknowledges them at the
 * minimum rate ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-026}, {@code API-PROD-020}; finding ⑥ of the M3
 * side security review). The server sends the first batch and then waits for a {@code ChunkBatchReceived},
 * while every chunk entering the ring of the moving player is still queued: the queue may hold no more
 * chunks than the ring has, (2r + 1)² for a ring of radius r, and what it drops must not leave the client a
 * hole.
 * <p>
 * The players are plain ones, not the test player that sends a chunk the moment it is queued. A move is
 * at most 48 blocks, within what one position packet may carry. Where the chunks are loaded up front, the
 * ring queues every chunk entering it on the tick thread as it moves, with no load completing in between.
 */
@EnvTest
public class ChunkQueueBoundIntegrationTest {

    /** A ring of radius 5, 121 chunks. */
    private static final int VIEW_DISTANCE = 4;
    private static final int RADIUS = VIEW_DISTANCE + 1;
    private static final double Y = 40;
    /** Three chunks along x. */
    private static final int STEP = 48;
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Test
    public void aWalkingClientThatNeverAcknowledgesQueuesNoMoreThanItsRing(Env env) {
        final int steps = 30;
        final Instance instance = env.createFlatInstance();
        preload(instance, -RADIUS, steps * 3 + RADIUS, -RADIUS, RADIUS);
        final TestConnection connection = env.createConnection();
        final Collector<ChunkDataPacket> chunks = connection.trackIncoming(ChunkDataPacket.class);
        final Player player = join(env, connection, instance);
        final int ring = ChunkRange.chunksCount(player.effectiveViewDistance());

        // One position packet a tick, and never a ChunkBatchReceived
        final List<Integer> queued = new ArrayList<>();
        for (int step = 1; step <= steps; step++) {
            player.addPacketToQueue(new ClientPlayerPositionPacket(new Vec(8.5 + STEP * step, Y, 8.5), true, false));
            env.tick();
            queued.add(queued(player));
        }

        assertEquals(steps * 3, player.getPosition().chunkX(), "every move is taken");
        final int sent = chunks.collect().size();
        System.out.println("Unacknowledged walk: ring " + ring + ", chunks sent " + sent + ", queued after each tick " + queued);
        assertEquals(9, sent, "the first batch, then nothing until a batch is acknowledged");
        assertTrue(Collections.max(queued) <= ring, "chunks queued after each tick, for a ring of " + ring + ": " + queued);
    }

    @Test
    public void aRacingClientThatAcknowledgesAtTheMinimumRateIsTrimmedToItsRingEveryTick(Env env) {
        final int ticks = 8, packetsPerTick = 4;
        final Instance instance = env.createFlatInstance();
        preload(instance, -RADIUS, ticks * packetsPerTick * 3 + RADIUS, -RADIUS, RADIUS);
        final TestConnection connection = env.createConnection();
        final Player player = join(env, connection, instance);

        // A receipt every tick asking for no chunk at all, clamped to the minimum of 0.01 a tick; twelve chunks a
        // tick in four position packets; the view distance down to 2 on odd ticks, back to 4 on even ones: the
        // ring moves several times within a tick, then shrinks to 49 chunks or grows back
        final List<String> queued = new ArrayList<>();
        boolean over = false;
        int step = 0;
        for (int tick = 1; tick <= ticks; tick++) {
            player.addPacketToQueue(new ClientChunkBatchReceivedPacket(0f));
            for (int packet = 0; packet < packetsPerTick; packet++) {
                step++;
                player.addPacketToQueue(new ClientPlayerPositionPacket(new Vec(8.5 + STEP * step, Y, 8.5), true, false));
            }
            player.addPacketToQueue(new ClientSettingsPacket(ClientSettings.defaultSettings(tick % 2 == 1 ? 2 : VIEW_DISTANCE)));
            env.tick();
            final int ring = ChunkRange.chunksCount(player.effectiveViewDistance());
            final int now = queued(player);
            queued.add(now + "/" + ring);
            over |= now > ring;
        }

        assertEquals(step * 3, player.getPosition().chunkX(), "every move is taken");
        System.out.println("Race at the minimum rate: queued/ring after each tick " + queued);
        assertFalse(over, "chunks queued / chunks of the ring after each tick: " + queued);
    }

    @Test
    public void chunksLoadedAfterTheRingMovedOnAreTrimmedAndLeaveNoHole(Env env) throws InterruptedException {
        final int late = 3 * (2 * RADIUS + 1);
        final CountDownLatch release = new CountDownLatch(1), handedOver = new CountDownLatch(late);
        final Instance instance = env.process().instance().createInstanceContainer();
        // East of the spawn ring the generation waits for the test: those chunks finish loading on their
        // virtual threads after the ring has left them, and are handed to the queue there
        instance.setGenerator(unit -> {
            if (unit.absoluteStart().chunkX() > RADIUS) await(release);
            unit.modifier().fillHeight(0, 40, Block.STONE);
        });
        try {
            preload(instance, -RADIUS, RADIUS, -RADIUS, RADIUS);
            preload(instance, -3 - RADIUS, -RADIUS - 1, -RADIUS, RADIUS);
            final TestConnection connection = env.createConnection();
            final Collector<ServerPacket> received = connection.trackIncoming();
            final Player player = join(env, connection, instance, (playerConnection, profile) -> new Player(playerConnection, profile) {
                @Override
                public void sendChunk(Chunk chunk) {
                    super.sendChunk(chunk);
                    if (chunk.getChunkX() > RADIUS) handedOver.countDown();
                }
            });

            // East to chunk 3, where the ring asks for x 6 to 8 and the generation holds them. Back to chunk 0,
            // which the chunk update limiter swallows, then on to chunk -3: x 6 to 8 are out of the ring
            moveTo(env, player, 8.5 + STEP);
            moveTo(env, player, 8.5);
            moveTo(env, player, 8.5 - STEP);
            assertEquals(-3, player.getPosition().chunkX(), "every move is taken");
            final int before = queued(player);

            release.countDown();
            assertTrue(handedOver.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "the late chunks are handed to the queue");
            final int completed = queued(player);
            env.tick();
            final int ring = ChunkRange.chunksCount(player.effectiveViewDistance());
            final int after = queued(player);
            System.out.println("Late loads: queued " + before + " before, " + completed + " once they completed, "
                    + after + " after the next tick, ring " + ring);
            assertTrue(after <= ring, "chunks queued after the tick that follows the late loads, for a ring of "
                    + ring + ": " + after);

            // From now on the client acknowledges: it ends up holding its whole ring and nothing else
            drain(env, player);
            assertHolds(ringAround(-3, 0), held(received.collect()), "the client");
        } finally {
            release.countDown();
        }
    }

    @Test
    public void aClientThatAcknowledgesStillGetsEveryChunkOfItsRing(Env env) {
        final int steps = 12;
        final Instance first = env.createFlatInstance();
        preload(first, -RADIUS, steps * 3 + RADIUS, -RADIUS, RADIUS);
        final Instance second = env.createFlatInstance();
        preload(second, -RADIUS, RADIUS, -RADIUS, RADIUS);
        final TestConnection connection = env.createConnection();
        final Collector<ServerPacket> received = connection.trackIncoming();
        final Player player = join(env, connection, first);

        // Acknowledging at 4 chunks a tick, the cap of a shard host, while the ring moves 3 chunks a tick: the
        // queue outgrows the ring on the way. The view distance goes down to 2 and back to 4 meanwhile
        int largest = 0;
        for (int step = 1; step <= steps; step++) {
            player.addPacketToQueue(new ClientChunkBatchReceivedPacket(4f));
            player.addPacketToQueue(new ClientPlayerPositionPacket(new Vec(8.5 + STEP * step, Y, 8.5), true, false));
            if (step == 4) player.addPacketToQueue(new ClientSettingsPacket(ClientSettings.defaultSettings(2)));
            if (step == 8) player.addPacketToQueue(new ClientSettingsPacket(ClientSettings.defaultSettings(VIEW_DISTANCE)));
            env.tick();
            largest = Math.max(largest, queued(player));
        }
        assertEquals(steps * 3, player.getPosition().chunkX(), "every move is taken");
        drain(env, player);
        System.out.println("Acknowledged walk: largest queue " + largest);
        assertHolds(ringAround(steps * 3, 0), held(received.collect()), "the client after the walk");

        // To another instance: the chunks sent from there on are the ring around the new spawn, each once
        final Collector<ChunkDataPacket> sent = connection.trackIncoming(ChunkDataPacket.class);
        player.acquirable().sync(owned -> owned.setInstance(second, new Pos(8.5, Y, 8.5)).join());
        drain(env, player);
        final List<Long> chunks = sent.collect().stream().map(chunk -> CoordConversion.chunkIndex(chunk.chunkX(), chunk.chunkZ())).toList();
        assertEquals(chunks.size(), new HashSet<>(chunks).size(), "a chunk sent twice after the instance change");
        assertHolds(ringAround(0, 0), new HashSet<>(chunks), "the chunks sent after the instance change");
    }

    private static Player join(Env env, TestConnection connection, Instance instance) {
        return join(env, connection, instance, Player::new);
    }

    /** Joins a plain player at view distance 4 in chunk (0, 0), its last teleport confirmed. */
    private static Player join(Env env, TestConnection connection, Instance instance, PlayerProvider provider) {
        env.process().connection().setPlayerProvider(provider);
        env.process().eventHandler().addListener(AsyncPlayerConfigurationEvent.class,
                event -> event.getPlayer().refreshSettings(ClientSettings.defaultSettings(VIEW_DISTANCE)));
        final Player player = connection.connect(instance, new Pos(8.5, Y, 8.5));
        assertEquals(RADIUS, player.effectiveViewDistance());
        player.addPacketToQueue(new ClientTeleportConfirmPacket(player.getLastSentTeleportId()));
        return player;
    }

    private static void moveTo(Env env, Player player, double x) {
        player.addPacketToQueue(new ClientPlayerPositionPacket(new Vec(x, Y, 8.5), true, false));
        env.tick();
    }

    /** Loads the chunks from (minX, minZ) to (maxX, maxZ), both included. */
    private static void preload(Instance instance, int minX, int maxX, int minZ, int maxZ) {
        final List<CompletableFuture<?>> loads = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) loads.add(instance.loadChunk(x, z));
        }
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).join();
    }

    /** Acknowledges a batch every tick, as a client does, until nothing is left to send. */
    private static void drain(Env env, Player player) {
        for (int tick = 0; tick < 50; tick++) {
            player.addPacketToQueue(new ClientChunkBatchReceivedPacket(64f));
            env.tick();
            if (queued(player) == 0) return;
        }
        fail("the queue still holds " + queued(player) + " chunks");
    }

    /** The chunks waiting in the send queue of the player, read under its queue lock. */
    private static int queued(Player player) {
        try {
            final ReentrantLock lock = (ReentrantLock) field("chunkQueueLock").get(player);
            lock.lock();
            try {
                final int queue = ((LongPriorityQueue) field("chunkQueue").get(player)).size();
                assertEquals(queue, ((LongSet) field("queuedChunks").get(player)).size(), "the queue and its index disagree");
                return queue;
            } finally {
                lock.unlock();
            }
        } catch (ReflectiveOperationException e) {
            throw new LinkageError(e.getMessage(), e);
        }
    }

    private static Field field(String name) throws NoSuchFieldException {
        final Field field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Set<Long> ringAround(int chunkX, int chunkZ) {
        final Set<Long> ring = new HashSet<>();
        ChunkRange.chunksInRange(chunkX, chunkZ, RADIUS, (x, z) -> ring.add(CoordConversion.chunkIndex(x, z)));
        return ring;
    }

    /** The chunks a client holds after the packets it was sent: a chunk is held from its data to its unload. */
    private static Set<Long> held(List<ServerPacket> packets) {
        final Set<Long> held = new LinkedHashSet<>();
        for (ServerPacket packet : packets) {
            switch (packet) {
                case ChunkDataPacket chunk -> held.add(CoordConversion.chunkIndex(chunk.chunkX(), chunk.chunkZ()));
                case UnloadChunkPacket unload -> held.remove(CoordConversion.chunkIndex(unload.chunkX(), unload.chunkZ()));
                default -> {
                }
            }
        }
        return held;
    }

    private static void assertHolds(Set<Long> expected, Set<Long> actual, String who) {
        final List<String> missing = new ArrayList<>(), extra = new ArrayList<>();
        for (long chunk : expected) if (!actual.contains(chunk)) missing.add(name(chunk));
        for (long chunk : actual) if (!expected.contains(chunk)) extra.add(name(chunk));
        assertTrue(missing.isEmpty() && extra.isEmpty(), who + " misses " + missing.size() + " chunks of the ring "
                + missing + " and holds " + extra.size() + " outside it " + extra);
    }

    private static String name(long chunk) {
        return CoordConversion.chunkIndexGetX(chunk) + "," + CoordConversion.chunkIndexGetZ(chunk);
    }
}
