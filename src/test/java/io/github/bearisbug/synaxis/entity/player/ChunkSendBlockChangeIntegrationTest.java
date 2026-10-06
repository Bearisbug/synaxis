package io.github.bearisbug.synaxis.entity.player;

import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.event.player.PlayerChunkLoadEvent;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.DynamicChunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChunkBatchReceivedPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTeleportConfirmPacket;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.BlockChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkBatchFinishedPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkBatchStartPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerPositionAndLookPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UnloadChunkPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UpdateViewPositionPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.ChunkData;
import io.github.bearisbug.synaxis.network.player.ClientSettings;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import it.unimi.dsi.fastutil.longs.LongPriorityQueue;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A block changed around the moment its chunk is sent reaches the client ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-020}, the order of a chunk packet and the block changes of its chunk). The block change of
 * {@code InstanceContainer#setBlock} goes to the viewers of the chunk, and a client drops the block change
 * of a chunk it does not hold: a change made after the chunk packet was taken and sent to the client before
 * that packet is lost for good, the client keeps the chunk as the packet had it. The same goes for a change
 * sent to the viewers of a chunk the client holds while the player is not one of them. The send takes the
 * lock of the chunk for this, under the queue lock of the player, and must not wait for it without a bound, nor
 * let a chunk whose lock is held hold back the rest of the batch.
 * <p>
 * The players are engine players, whose chunks wait in the send queue, at view distance 2: a ring of radius 3. They
 * only watch what is handed to their connection ({@link CheckedPlayer}). The client is modelled from the packets it was
 * sent, in the order they were handed to its connection.
 */
@EnvTest
public class ChunkSendBlockChangeIntegrationTest {

    private static final int VIEW_DISTANCE = 2;
    private static final int RADIUS = VIEW_DISTANCE + 1;
    private static final double Y = 40;
    /** Air in the flat world, which fills up to y 39. */
    private static final int CHANGED_Y = 45;
    private static final Duration WAIT = Duration.ofSeconds(10);
    /** How long the thread holding a chunk lock waits for the queue lock of the player before it calls it a deadlock. */
    private static final Duration DEADLOCK = Duration.ofSeconds(5);

    @Test
    public void aBlockChangedRightAfterTheChunkPacketWasTakenReachesTheClient(Env env) throws InterruptedException {
        final Window window = new Window();
        final InstanceContainer instance = takenChunkInstance(env, window::taken);
        preload(instance, -RADIUS, RADIUS, -RADIUS, RADIUS);
        final TestConnection connection = env.createConnection();
        final Collector<ServerPacket> received = connection.trackIncoming();
        final List<String> underTheLock = new CopyOnWriteArrayList<>();
        env.process().eventHandler().addListener(PlayerChunkLoadEvent.class, event -> {
            final Chunk chunk = instance.getChunk(event.getChunkX(), event.getChunkZ());
            if (chunk != null && chunk.holdsWriteLock()) underTheLock.add(event.getChunkX() + "," + event.getChunkZ());
        });
        final CheckedPlayer player = join(env, connection, instance);

        // The first batch is out. The next chunk packet taken stops its thread right after the chunk let go of its
        // own write-lock, as a SynxChunk overlay does, until another thread has changed a block of that chunk or
        // waits for its lock to do so
        final Thread setter = Thread.ofPlatform().name("setter").start(() -> {
            final Chunk chunk = window.taken.join();
            final int x = chunk.getChunkX() * 16 + 1, z = chunk.getChunkZ() * 16 + 1;
            instance.setBlock(x, CHANGED_Y, z, Block.GOLD_BLOCK);
        });
        window.arm(setter);
        player.addPacketToQueue(new ClientChunkBatchReceivedPacket(64f));
        env.tick();
        setter.join(WAIT.toMillis());
        assertFalse(setter.isAlive(), "the block change is made");

        final Chunk chunk = window.taken.getNow(null);
        assertNotNull(chunk, "a chunk packet is taken in the tick");
        assertFalse(window.timedOut.get(), "the setter neither changes the block nor waits for the chunk lock");
        final int x = chunk.getChunkX() * 16 + 1, z = chunk.getChunkZ() * 16 + 1;
        final Block server = instance.getBlock(x, CHANGED_Y, z);
        final Block client = clientBlock(received.collect(), chunk, x, CHANGED_Y, z);
        System.out.println("Chunk " + chunk.getChunkX() + "," + chunk.getChunkZ() + ": the setter "
                + (window.setterWaited.get() ? "waited for the chunk lock" : "changed the block before the packet was queued")
                + ", the client sees " + client.name() + ", the server has " + server.name() + "; " + player.chunksHanded.size()
                + " chunk packets handed to the connection, " + player.handedOutsideTheLock.size() + " outside the chunk write-lock");
        assertEquals(Block.GOLD_BLOCK, server);
        assertEquals(server.name(), client.name(), "the block at " + x + "," + CHANGED_Y + "," + z + " the client sees");
        assertTrue(underTheLock.isEmpty(), "PlayerChunkLoadEvent dispatched under the chunk write-lock for " + underTheLock);
        assertTrue(player.handedOutsideTheLock.isEmpty(), "chunk packets handed to the connection outside the chunk write-lock for "
                + player.handedOutsideTheLock);
    }

    @Test
    public void aThreadQueuingChunksUnderTheLockOfTheNextChunkToSendIsNotDeadlocked(Env env) throws Exception {
        final AtomicReference<Consumer<Chunk>> hook = new AtomicReference<>(_ -> {
        });
        final InstanceContainer instance = takenChunkInstance(env, chunk -> hook.get().accept(chunk));
        preload(instance, -RADIUS, RADIUS + 1, -RADIUS, RADIUS);
        final TestConnection connection = env.createConnection();
        final Player player = join(env, connection, instance);
        drain(env, player);
        final ReentrantLock queueLock = (ReentrantLock) field("chunkQueueLock").get(player);

        // Moving one chunk east queues the column at x 4, and its chunk at z 0, the closest to the new center, is sent
        // first. While its packet is taken, another thread takes the write-locks of the rest of the column, then asks for
        // the queue lock of the player, as Chunk#sendChunk does under the lock of a chunk; the send holds that queue
        // lock and goes on to the next chunk of the column
        final int column = 1 + RADIUS;
        final List<Chunk> rest = new ArrayList<>();
        for (int z = -RADIUS; z <= RADIUS; z++) {
            if (z != 0) rest.add(Objects.requireNonNull(instance.getChunk(column, z)));
        }
        final CountDownLatch locked = new CountDownLatch(1);
        final AtomicBoolean queueLockTaken = new AtomicBoolean();
        final AtomicLong queueLockWait = new AtomicLong(-1);
        final AtomicReference<Thread> holder = new AtomicReference<>();
        hook.set(chunk -> {
            if (chunk.getChunkX() != column || chunk.getChunkZ() != 0 || holder.get() != null) return;
            holder.set(Thread.ofPlatform().name("holder").start(() -> {
                rest.forEach(Chunk::lockWriteLock);
                try {
                    locked.countDown();
                    final long start = System.nanoTime();
                    if (queueLock.tryLock(DEADLOCK.toNanos(), TimeUnit.NANOSECONDS)) {
                        queueLockTaken.set(true);
                        queueLock.unlock();
                    }
                    queueLockWait.set(System.nanoTime() - start);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                } finally {
                    rest.forEach(Chunk::unlockWriteLock);
                }
            }));
            await(locked);
        });
        final Collector<ChunkDataPacket> sent = connection.trackIncoming(ChunkDataPacket.class);
        moveTo(env, player, 8.5 + 16);
        final Thread thread = holder.get();
        assertNotNull(thread, "chunk " + column + ",0 is sent in the tick of the move");
        thread.join(WAIT.toMillis());
        assertFalse(thread.isAlive(), "the thread holding the chunk locks ends");
        drain(env, player);

        final Map<String, Long> times = new TreeMap<>();
        for (ChunkDataPacket chunk : sent.collect()) times.merge(chunk.chunkX() + "," + chunk.chunkZ(), 1L, Long::sum);
        System.out.println("Column " + column + " locked by another thread after its first chunk: the holder waited "
                + TimeUnit.NANOSECONDS.toMicros(queueLockWait.get()) + " µs for the queue lock and took it "
                + queueLockTaken.get() + ", chunks sent " + times);
        assertTrue(queueLockTaken.get(), "the thread holding the write-locks of column " + column + " waited "
                + TimeUnit.NANOSECONDS.toMillis(queueLockWait.get()) + " ms for the queue lock of the player sending it: a deadlock");
        assertEquals(2 * RADIUS + 1, times.size(), "chunks of column " + column + " sent " + times);
        assertTrue(times.values().stream().allMatch(count -> count == 1), "each chunk sent once: " + times);
    }

    @Test
    public void aChunkTheClientHoldsGetsItsBlockChangesWhileTheChunkUpdateLimiterHoldsTheCenterBack(Env env) {
        final Instance instance = env.createFlatInstance();
        preload(instance, -RADIUS, RADIUS + 1, -RADIUS, RADIUS);
        final TestConnection connection = env.createConnection();
        final Collector<ServerPacket> received = connection.trackIncoming();
        final Player player = join(env, connection, instance);
        drain(env, player);

        // East to chunk 1: the center follows and the column at x 4 is sent. Back to chunk 0, which the chunk update
        // limiter remembers: the center stays at chunk 1, and the column at x 4 is still on the client, 4 chunks from
        // the player, one more than the radius
        moveTo(env, player, 8.5 + 16);
        drain(env, player);
        moveTo(env, player, 8.5);
        assertEquals(0, player.getPosition().chunkX(), "the move back is taken");
        final int edge = 1 + RADIUS;
        final int x = edge * 16 + 1, z = 1;
        final Chunk chunk = instance.getChunk(edge, 0);
        assertNotNull(chunk);
        instance.setBlock(x, CHANGED_Y, z, Block.GOLD_BLOCK);
        // East again, where the column is in reach of the position as well
        moveTo(env, player, 8.5 + 16);

        final List<ServerPacket> packets = received.collect();
        final UpdateViewPositionPacket center = packets.stream().filter(UpdateViewPositionPacket.class::isInstance)
                .map(UpdateViewPositionPacket.class::cast).reduce((_, last) -> last).orElseThrow();
        final Block client = clientBlock(packets, chunk, x, CHANGED_Y, z);
        System.out.println("Center told to the client " + center.chunkX() + "," + center.chunkZ() + ", the client sees "
                + client.name() + " in chunk " + edge + ",0");
        assertEquals(1, center.chunkX(), "the center the client was last told");
        assertEquals(Block.GOLD_BLOCK.name(), client.name(), "the block at " + x + "," + CHANGED_Y + "," + z + " the client sees");
    }

    @Test
    public void theChunksBehindAChunkWhoseLockIsHeldAreSentAndTheBatchWaitsOnce(Env env) {
        final InstanceContainer instance = takenChunkInstance(env, _ -> {
        });
        preload(instance, -RADIUS, RADIUS + 1, -RADIUS, RADIUS);
        final TestConnection connection = env.createConnection();
        final CheckedPlayer player = join(env, connection, instance);
        drain(env, player);

        // Moving one chunk east queues the column at x 4, the closest to the new center first: (4, 0), (4, ±1), (4, ±2),
        // (4, ±3). Another thread holds the write-locks of all of them but (4, -3) through the batch of the move, from its
        // start to its end: the tick of a chunk takes its read-lock. Their packets are taken once before, so the batch
        // only hands them over. The holder notes the locks the sending thread waits for: a try without a wait does not
        // queue for the lock, a wait does. How long the batch takes is printed, not checked: the scheduling of the test
        // threads, run beside other test classes, adds to it
        final int column = 1 + RADIUS;
        final List<Chunk> held = new ArrayList<>();
        for (int z = -RADIUS; z <= RADIUS; z++) {
            final Chunk chunk = Objects.requireNonNull(instance.getChunk(column, z));
            chunk.getFullDataPacket();
            if (z != -RADIUS) held.add(chunk);
        }
        final AtomicReference<Holder> holder = new AtomicReference<>();
        player.onBatchStart = () -> holder.set(new Holder(held, Thread.currentThread()));
        player.onBatchEnd = () -> holder.get().letGo();
        final Collector<ChunkDataPacket> inTheMove = connection.trackIncoming(ChunkDataPacket.class);
        moveTo(env, player, 8.5 + 16);
        player.onBatchStart = () -> {
        };
        player.onBatchEnd = () -> {
        };
        final long batch = player.lastBatchNanos;
        final List<String> sentInTheMove = names(inTheMove.collect());
        assertNotNull(holder.get(), "a batch in the tick of the move");
        assertFalse(holder.get().thread.isAlive(), "the thread holding the chunk locks ends with the batch");
        final Collector<ChunkDataPacket> afterTheMove = connection.trackIncoming(ChunkDataPacket.class);
        drain(env, player);

        final Map<String, Long> times = new TreeMap<>();
        for (String chunk : sentInTheMove) times.merge(chunk, 1L, Long::sum);
        for (String chunk : names(afterTheMove.collect())) times.merge(chunk, 1L, Long::sum);
        final Set<String> waitedFor = holder.get().waitedFor;
        System.out.println("Column " + column + " but its chunk " + column + "," + -RADIUS + " locked by another thread: sent in the tick of"
                + " the move " + sentInTheMove + ", the batch waited for the locks of " + waitedFor + " and took "
                + TimeUnit.NANOSECONDS.toMicros(batch) + " µs, chunks sent " + times);
        assertEquals(List.of(column + "," + -RADIUS), sentInTheMove, "the chunks of column " + column + " sent in the tick of the move");
        assertTrue(waitedFor.size() <= 1, "the batch waited for the locks of " + waitedFor + ", " + held.size() + " held by another thread");
        assertEquals(2 * RADIUS + 1, times.size(), "chunks of column " + column + " sent " + times);
        assertTrue(times.values().stream().allMatch(count -> count == 1), "each chunk sent once: " + times);
    }

    @Test
    public void thePositionSyncOfTheFirstChunksFollowsAChunkWhenTheFirstBatchIsEmpty(Env env) {
        final InstanceContainer instance = takenChunkInstance(env, _ -> {
        });
        preload(instance, -RADIUS, RADIUS, -RADIUS, RADIUS);
        // Another thread holds the write-locks of the chunks at most 2 from the spawn chunk, x and z distances added as the
        // queue orders them, while the player joins: the 9 chunks the first batch may send are among them
        final List<Chunk> held = new ArrayList<>();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (Math.abs(x) + Math.abs(z) <= 2) held.add(Objects.requireNonNull(instance.getChunk(x, z)));
            }
        }
        final Holder holder = new Holder(held, null);
        final TestConnection connection = env.createConnection();
        final Collector<ServerPacket> received = connection.trackIncoming();
        final Player player = join(env, connection, instance);
        holder.letGo();
        assertFalse(holder.thread.isAlive(), "the thread holding the chunk locks ends");
        drain(env, player);

        final List<ServerPacket> packets = received.collect();
        final List<Integer> batches = new ArrayList<>(), syncs = new ArrayList<>();
        int firstChunk = -1;
        for (int i = 0; i < packets.size(); i++) {
            switch (packets.get(i)) {
                case ChunkBatchFinishedPacket finished -> batches.add(finished.batchSize());
                case PlayerPositionAndLookPacket _ -> syncs.add(i);
                case ChunkDataPacket _ -> {
                    if (firstChunk < 0) firstChunk = i;
                }
                default -> {
                }
            }
        }
        System.out.println("Chunks within 2 of the spawn chunk locked by another thread while joining: batches " + batches
                + ", the first chunk packet at " + firstChunk + ", position syncs at " + syncs);
        assertEquals(0, batches.getFirst(), "the size of the first batch, its chunks locked by another thread");
        final int first = firstChunk;
        assertTrue(first >= 0 && syncs.stream().anyMatch(sync -> sync > first),
                "a position sync after the first chunk packet, at " + first + ": position syncs at " + syncs);
    }

    /** A flat instance of {@link TakenChunk}s that call {@code taken} once they took their packet. */
    private static InstanceContainer takenChunkInstance(Env env, Consumer<Chunk> taken) {
        final InstanceContainer instance = env.process().instance().createInstanceContainer();
        instance.setChunkSupplier((owner, chunkX, chunkZ) -> new TakenChunk(owner, chunkX, chunkZ, taken));
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
        return instance;
    }

    /**
     * Takes its packet the way a {@code SynxChunk} overlay does: frozen under its own write-lock when asked for it,
     * then calls {@code taken} on the thread that asked, the write-lock of the chunk let go.
     */
    private static final class TakenChunk extends DynamicChunk {
        private final Consumer<Chunk> taken;

        TakenChunk(Instance instance, int chunkX, int chunkZ, Consumer<Chunk> taken) {
            super(instance, chunkX, chunkZ);
            this.taken = taken;
        }

        @Override
        public SendablePacket getFullDataPacket() {
            final ServerPacket packet;
            lockWriteLock();
            try {
                packet = ((CachedPacket) super.getFullDataPacket()).packet();
            } finally {
                unlockWriteLock();
            }
            taken.accept(this);
            return packet;
        }
    }

    /** The window between taking a chunk packet and handing it to the connection. */
    private static final class Window {
        final CompletableFuture<Chunk> taken = new CompletableFuture<>();
        final AtomicBoolean setterWaited = new AtomicBoolean(), timedOut = new AtomicBoolean();
        private final AtomicBoolean armed = new AtomicBoolean();
        private volatile Thread setter;

        void arm(Thread setter) {
            this.setter = setter;
            armed.set(true);
        }

        /** Holds the thread that took the packet of {@code chunk} until the setter has changed it, or waits for its lock. */
        void taken(Chunk chunk) {
            if (!armed.compareAndSet(true, false)) return;
            final Thread setter = this.setter;
            taken.complete(chunk);
            final ReentrantReadWriteLock lock = chunkLock(chunk);
            final long deadline = System.nanoTime() + WAIT.toNanos();
            while (setter.isAlive() && !lock.hasQueuedThread(setter)) {
                if (System.nanoTime() - deadline > 0) {
                    timedOut.set(true);
                    return;
                }
                LockSupport.parkNanos(100_000);
            }
            setterWaited.set(setter.isAlive());
        }
    }

    /**
     * An engine player that watches what is handed to its connection: whether a {@code ChunkDataPacket}, which a
     * {@link TakenChunk} hands out as it is, is handed under the write-lock of its chunk, and how long the last chunk
     * batch took from its start to its end. Runs {@link #onBatchStart} and {@link #onBatchEnd} on the sending thread,
     * the start before the batch is timed.
     */
    private static final class CheckedPlayer extends Player {
        final List<String> chunksHanded = new CopyOnWriteArrayList<>(), handedOutsideTheLock = new CopyOnWriteArrayList<>();
        volatile Runnable onBatchStart = () -> {
        }, onBatchEnd = () -> {
        };
        volatile long lastBatchNanos = -1;
        private volatile long batchStart;

        CheckedPlayer(PlayerConnection connection, GameProfile profile) {
            super(connection, profile);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            switch (packet) {
                case ChunkBatchStartPacket _ -> {
                    onBatchStart.run();
                    batchStart = System.nanoTime();
                }
                case ChunkBatchFinishedPacket _ -> {
                    lastBatchNanos = System.nanoTime() - batchStart;
                    onBatchEnd.run();
                }
                case ChunkDataPacket data -> {
                    final String at = data.chunkX() + "," + data.chunkZ();
                    chunksHanded.add(at);
                    final Chunk chunk = getInstance().getChunk(data.chunkX(), data.chunkZ());
                    if (chunk == null || !chunk.holdsWriteLock()) handedOutsideTheLock.add(at);
                }
                default -> {
                }
            }
            super.sendPacket(packet);
        }
    }

    /**
     * The block the client sees at (x, y, z) of {@code chunk} after the packets it was sent: a chunk is held from its
     * data to its unload, and a block change of a chunk the client does not hold is dropped, as the client drops it.
     */
    private static Block clientBlock(List<ServerPacket> packets, Chunk chunk, int x, int y, int z) {
        final long index = CoordConversion.chunkIndex(chunk.getChunkX(), chunk.getChunkZ());
        final Map<Long, ChunkDataPacket> held = new HashMap<>();
        final Map<Integer, Integer> changed = new HashMap<>();
        for (ServerPacket packet : packets) {
            switch (packet) {
                case ChunkDataPacket data -> {
                    final long at = CoordConversion.chunkIndex(data.chunkX(), data.chunkZ());
                    held.put(at, data);
                    if (at == index) changed.clear();
                }
                case UnloadChunkPacket unload -> {
                    final long at = CoordConversion.chunkIndex(unload.chunkX(), unload.chunkZ());
                    held.remove(at);
                    if (at == index) changed.clear();
                }
                case BlockChangePacket change -> {
                    final long at = CoordConversion.chunkIndex(change.blockPosition().chunkX(), change.blockPosition().chunkZ());
                    if (held.containsKey(at) && at == index) {
                        changed.put(CoordConversion.chunkBlockIndex(change.blockPosition().blockX(),
                                change.blockPosition().blockY(), change.blockPosition().blockZ()), change.blockStateId());
                    }
                }
                default -> {
                }
            }
        }
        final ChunkDataPacket data = held.get(index);
        if (data == null) fail("the client does not hold chunk " + chunk.getChunkX() + "," + chunk.getChunkZ());
        final Integer state = changed.get(CoordConversion.chunkBlockIndex(x, y, z));
        if (state != null) return Objects.requireNonNull(Block.fromStateId(state));
        final byte[] bytes = data.chunkData().data();
        final NetworkBuffer buffer = NetworkBuffer.wrap(bytes, 0, bytes.length);
        final NetworkBuffer.Type<ChunkData.Section> type = ChunkData.Section.networkType(chunk.getInstance().process().biome().size());
        ChunkData.Section section = null;
        for (int i = (y >> 4) - chunk.getMinSection(); i >= 0; i--) section = buffer.read(type);
        assertNotNull(section);
        return Objects.requireNonNull(Block.fromStateId(section.blockStates().get(x & 15, y & 15, z & 15)));
    }

    /** Joins a {@link CheckedPlayer} at view distance 2 in chunk (0, 0), its last teleport confirmed. */
    private static CheckedPlayer join(Env env, TestConnection connection, Instance instance) {
        env.process().connection().setPlayerProvider(CheckedPlayer::new);
        env.process().eventHandler().addListener(AsyncPlayerConfigurationEvent.class,
                event -> event.getPlayer().refreshSettings(ClientSettings.defaultSettings(VIEW_DISTANCE)));
        final CheckedPlayer player = (CheckedPlayer) connection.connect(instance, new Pos(8.5, Y, 8.5));
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
                return ((LongPriorityQueue) field("chunkQueue").get(player)).size();
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

    /**
     * Another thread that takes the write-locks of some chunks once constructed and holds them until {@link #letGo()}, at
     * most {@link #WAIT}. Meanwhile it notes the chunks whose lock {@code watched} is queued for: a thread waiting for a
     * lock is, a thread only trying it is not ({@code ReentrantReadWriteLock#hasQueuedThread}).
     */
    private static final class Holder {
        final Set<String> waitedFor = ConcurrentHashMap.newKeySet();
        final Thread thread;
        private final CountDownLatch release = new CountDownLatch(1);

        Holder(List<Chunk> chunks, @Nullable Thread watched) {
            final CountDownLatch locked = new CountDownLatch(1);
            this.thread = Thread.ofPlatform().name("holder").start(() -> {
                chunks.forEach(Chunk::lockWriteLock);
                try {
                    locked.countDown();
                    final long deadline = System.nanoTime() + WAIT.toNanos();
                    while (release.getCount() > 0 && System.nanoTime() - deadline < 0) {
                        if (watched != null) {
                            for (Chunk chunk : chunks) {
                                if (chunkLock(chunk).hasQueuedThread(watched)) waitedFor.add(chunk.getChunkX() + "," + chunk.getChunkZ());
                            }
                        }
                        LockSupport.parkNanos(10_000);
                    }
                    if (release.getCount() > 0) System.out.println("The holder lets go after " + WAIT);
                } finally {
                    chunks.forEach(Chunk::unlockWriteLock);
                }
            });
            await(locked);
        }

        /** Lets go of the locks and waits for the thread to end, at most {@link #WAIT}. */
        void letGo() {
            release.countDown();
            try {
                thread.join(WAIT.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static List<String> names(List<ChunkDataPacket> chunks) {
        return chunks.stream().map(chunk -> chunk.chunkX() + "," + chunk.chunkZ()).toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "the other thread is ready");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static ReentrantReadWriteLock chunkLock(Chunk chunk) {
        try {
            final Field field = Chunk.class.getDeclaredField("lock");
            field.setAccessible(true);
            return (ReentrantReadWriteLock) field.get(chunk);
        } catch (ReflectiveOperationException e) {
            throw new LinkageError(e.getMessage(), e);
        }
    }
}
