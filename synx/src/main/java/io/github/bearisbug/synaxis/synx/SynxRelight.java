package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerChunkLoadEvent;
import io.github.bearisbug.synaxis.event.player.PlayerChunkUnloadEvent;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.light.LightEngine;
import io.github.bearisbug.synaxis.instance.light.LightResult;
import io.github.bearisbug.synaxis.instance.light.WorldView;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.network.packet.server.play.UpdateLightPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.LightData;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The runtime light of a map instance ({@code docs/DESIGN-M3.md} {@code API-M3-005} 运行时光照,
 * {@code ADR-M3-002}, {@code ADR-M3-019}).
 * <p>
 * A change of block state in a {@link SynxChunk} queues a recomputation of the 3×3 around its chunk,
 * the center: one entry per center, later changes merged into a queued one or marked to run again
 * once a running one is installed. {@code relight_pending} counts, per chunk position, the centers
 * queued or running whose 3×3 holds it. A recomputation takes tickets on the 5×5 around its center,
 * copies their blocks under their read-locks ({@link #snapshot}) and runs
 * {@link LightEngine#relightArea} on the copy ({@link #compute}), on threads of its own; a position
 * that is not a {@code SynxChunk} is missing and passes no light, as in the baker. The result waits
 * for the end of a tick, where the tick scheduling thread installs it ({@link #installPhase}), each
 * target only if the 3×3 around it still holds the chunk objects and the {@code block_changes} of the
 * snapshot, and only the sections whose light it does not hold yet: the recomputation of a neighbouring
 * change may have installed the same light before. The players whose client cannot compute the light
 * of the change itself get the full light of the target then; the others compute it from the block change.
 */
public final class SynxRelight {
    private static final Logger LOGGER = LoggerFactory.getLogger(SynxRelight.class);

    /** Target entries waiting for the install from which on the recomputation threads take no new center. */
    static final int INSTALL_BACKLOG = 256;
    /** Target entries an install handles at most; it stops earlier once it took {@link #INSTALL_BUDGET_NANOS}. */
    static final int INSTALL_ENTRIES_PER_TICK = 64;
    private static final long INSTALL_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    /**
     * How many ticks a chunk has to be delivered, or its light sent, before a client computes the light
     * of a change next to it itself: the light of a delivered chunk waits in a queue on the client and
     * overwrites what it computed until it is applied (26.2, {@code ClientLevel#pollLightUpdates}).
     */
    static final long SELF_COMPUTE_TICKS = 100;
    private static final Duration MATERIALIZE_WAIT = Duration.ofMillis(50);
    private static final long SLOW_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    private static final long DRAIN_CAP_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final int WINDOW_SAMPLES = 1 << 16;
    private static final byte[] DARK = new byte[2048];
    private static final byte[][] NONE = new byte[0][];

    /** A center queued or running, one at most per chunk position; guarded by {@link #lock}. */
    private static final class Center {
        final int x;
        final int z;
        final long firstChange;
        boolean running;
        boolean again;
        @Nullable Task task;

        Center(int x, int z, long firstChange) {
            this.x = x;
            this.z = z;
            this.firstChange = firstChange;
        }
    }

    /**
     * A position of a snapshot: the chunk object with its {@code block_changes} at the time and the
     * palettes it had; chunk null for a missing position, palettes null for air and once the light is
     * computed.
     */
    private record Taken(@Nullable SynxChunk chunk, long blockChanges, Palette @Nullable [] blocks) {
    }

    /** A target of a finished computation, waiting for the install; chunk null for one whose light is not written. */
    private record Target(Task task, int x, int z, @Nullable SynxChunk chunk, byte[] @Nullable [] sky,
                          byte[] @Nullable [] block, boolean changed) {
        /** A target whose light is not written: missing, failed, or outside the region. */
        static Target notWritten(Task task, int x, int z) {
            return new Target(task, x, z, null, NONE, NONE, false);
        }
    }

    /** One run of a center: its tickets, its snapshot, which is the view of its computation, and its targets left. */
    private final class Task implements WorldView {
        final Center center;
        final Taken[] taken = new Taken[25];
        final LongArrayList tickets = new LongArrayList(25);
        // The install's, but read by a drain on another thread
        final AtomicInteger left = new AtomicInteger(9);
        volatile boolean computed;

        Task(Center center) {
            this.center = center;
        }

        /** Once the light is computed: the install needs the chunk objects and their {@code block_changes} only. */
        void dropBlocks() {
            for (int i = 0; i < taken.length; i++) {
                final Taken at = taken[i];
                if (at != null && at.blocks() != null) taken[i] = new Taken(at.chunk(), at.blockChanges(), null);
            }
        }

        @Nullable Taken at(int chunkX, int chunkZ) {
            final int dx = chunkX - center.x, dz = chunkZ - center.z;
            if (dx < -2 || dx > 2 || dz < -2 || dz > 2) return null;
            return taken[(dx + 2) * 5 + dz + 2];
        }

        @Override
        public int minY() {
            return minY;
        }

        @Override
        public int height() {
            return sectionCount * 16;
        }

        @Override
        public boolean hasChunk(int chunkX, int chunkZ) {
            final Taken at = at(chunkX, chunkZ);
            return at != null && at.chunk() != null;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            final Palette[] blocks = Objects.requireNonNull(at(x >> 4, z >> 4)).blocks();
            if (blocks == null) return Block.AIR;
            final Palette section = blocks[(y - minY) >> 4];
            return Objects.requireNonNullElse(Block.fromStateId(section.get(x & 15, y & 15, z & 15)), Block.AIR);
        }
    }

    private final SynxChunkLoader loader;
    private final InstanceContainer instance;
    private final Executor executor;
    // The pool of a shard, which runs the snapshot and the computation of a center on one thread; null
    // when the tests hand both to an executor of theirs, one after the other
    private final @Nullable ThreadPoolExecutor pool;
    private final int minY;
    private final int minSection;
    private final int sectionCount;

    private final Object lock = new Object();
    private final Map<Long, Center> centers = new HashMap<>();
    // Written under the lock, read anywhere
    private final Map<Long, Integer> pending = new ConcurrentHashMap<>();

    private final Queue<Target> installs = new ConcurrentLinkedQueue<>();
    private final AtomicInteger waiting = new AtomicInteger();
    private final Object room = new Object();
    // A drain on a dead tick installs on the shutdown thread, maybe while the tick scheduler still does
    private final ReentrantLock installing = new ReentrantLock();

    private final LongAdder relights = new LongAdder();
    private final LongAdder skipped = new LongAdder();
    private final LongAdder lightMaterialized = new LongAdder();
    private final LongAdder lightPackets = new LongAdder();
    private final LongAdder lightSections = new LongAdder();
    private final LongArrayList queueSamples = new LongArrayList();
    private final LongArrayList relightSamples = new LongArrayList();
    private final AtomicLong installMaxNanos = new AtomicLong();

    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final AtomicLong drainDeadline = new AtomicLong(Long.MAX_VALUE);
    private volatile boolean draining;
    private volatile long drainStart;

    SynxRelight(SynxChunkLoader loader, InstanceContainer instance, @Nullable Executor computeExecutor) {
        this.loader = loader;
        this.instance = instance;
        // The world of the release, which the chunks of the instance are held to (SynxChunkLoader#requireWorld)
        this.minY = loader.world().minY();
        this.minSection = Math.floorDiv(minY, 16);
        this.sectionCount = loader.world().height() / 16;
        if (computeExecutor != null) {
            this.pool = null;
            this.executor = computeExecutor;
        } else {
            final int threads = loader.options().relightThreads();
            final ThreadPoolExecutor created = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(), Thread.ofPlatform().daemon().name("synaxis-relight-", 0).factory());
            created.allowCoreThreadTimeOut(true);
            this.pool = created;
            this.executor = created;
        }
    }

    /** Listens to the deliveries of chunks to the players of the instance; once, right after the construction. */
    void listen() {
        instance.eventNode().addListener(PlayerChunkLoadEvent.class, this::delivered);
        instance.eventNode().addListener(PlayerChunkUnloadEvent.class, this::undelivered);
    }

    InstanceContainer instance() {
        return instance;
    }

    // ---- Queueing, on the thread that changes a block, under the write-lock of its chunk

    /**
     * A block of {@code chunk} changed its state: queues the recomputation of the 3×3 around it and
     * marks the players who hold a target but cannot compute its light themselves. {@code relight_pending}
     * goes up before the delivered players are read (§16 区块交付与改方块同时发生).
     */
    void changed(SynxChunk chunk) {
        final int chunkX = chunk.chunkX(), chunkZ = chunk.chunkZ();
        synchronized (lock) {
            final Center center = centers.get(key(chunkX, chunkZ));
            if (center == null) {
                final Center created = new Center(chunkX, chunkZ, System.nanoTime());
                centers.put(key(chunkX, chunkZ), created);
                for (int x = chunkX - 1; x <= chunkX + 1; x++) {
                    for (int z = chunkZ - 1; z <= chunkZ + 1; z++) pending.merge(key(x, z), 1, Integer::sum);
                }
                submit(created);
            } else if (center.running) {
                center.again = true;
            }
        }
        judge(chunk);
    }

    /**
     * Which players can compute the light of this change themselves, target by target: a player can
     * for a target T when every chunk of T's 3×3 is a {@code SynxChunk} delivered to them 100 ticks ago
     * or longer, none of them with them in its {@code light_stale}. The center is in T's 3×3, so such a
     * player holds it and gets the block change. The others go into T's {@code light_stale}, a resend
     * due whatever the install finds if they hold the center: they computed the light from partial
     * input. Each player is looked at once for the 5×5.
     */
    private void judge(SynxChunk center) {
        final long now = instance.getWorldAge();
        final int centerX = center.chunkX(), centerZ = center.chunkZ();
        final SynxChunk[] around = new SynxChunk[25];
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (instance.getChunk(centerX + dx, centerZ + dz) instanceof SynxChunk chunk) around[(dx + 2) * 5 + dz + 2] = chunk;
            }
        }
        final Map<Player, boolean[]> judged = new HashMap<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                final SynxChunk target = around[(dx + 2) * 5 + dz + 2];
                if (target == null || !target.lightWritable()) continue;
                for (Player player : target.delivered().keySet()) {
                    if (!player.isOnline()) {
                        target.delivered().remove(player);
                        target.lightStale().remove(player);
                        continue;
                    }
                    final boolean[] ready = judged.computeIfAbsent(player, p -> ready(p, around, now));
                    if (selfComputes(ready, dx, dz)) continue;
                    target.lightStale().merge(player, center.delivered().containsKey(player), Boolean::logicalOr);
                }
            }
        }
    }

    /** Per position of the 5×5: a {@code SynxChunk} delivered to the player 100 ticks ago or longer, light not stale. */
    private static boolean[] ready(Player player, SynxChunk[] around, long now) {
        final boolean[] ready = new boolean[25];
        for (int i = 0; i < 25; i++) {
            final SynxChunk chunk = around[i];
            final Long at = chunk == null ? null : chunk.delivered().get(player);
            ready[i] = at != null && now - at >= SELF_COMPUTE_TICKS && !chunk.lightStale().containsKey(player);
        }
        return ready;
    }

    private static boolean selfComputes(boolean[] ready, int targetX, int targetZ) {
        for (int dx = targetX - 1; dx <= targetX + 1; dx++) {
            for (int dz = targetZ - 1; dz <= targetZ + 1; dz++) {
                if (!ready[(dx + 2) * 5 + dz + 2]) return false;
            }
        }
        return true;
    }

    /** Under the lock. */
    private void submit(Center center) {
        final Task task = new Task(center);
        center.task = task;
        executor.execute(() -> run(task));
    }

    // ---- Deliveries, on the tick thread of the player

    /** Delivered first, pending read second (§16). */
    private void delivered(PlayerChunkLoadEvent event) {
        if (!(instance.getChunk(event.getChunkX(), event.getChunkZ()) instanceof SynxChunk chunk)) return;
        final Player player = event.getPlayer();
        chunk.delivered().put(player, instance.getWorldAge());
        if (chunk.lightWritable() && pending(event.getChunkX(), event.getChunkZ()) > 0) {
            chunk.lightStale().merge(player, false, Boolean::logicalOr);
        }
    }

    private void undelivered(PlayerChunkUnloadEvent event) {
        if (!(instance.getChunk(event.getChunkX(), event.getChunkZ()) instanceof SynxChunk chunk)) return;
        chunk.delivered().remove(event.getPlayer());
        chunk.lightStale().remove(event.getPlayer());
    }

    // ---- The recomputation, on a recomputation thread

    private void run(Task task) {
        if (pool != null) awaitRoom();
        synchronized (lock) {
            task.center.running = true;
        }
        final long start = System.nanoTime();
        try {
            snapshot(task);
        } catch (RuntimeException e) {
            failed(task, e);
            return;
        }
        if (pool != null) computed(task, start);
        else executor.execute(() -> computed(task, start));
    }

    private void computed(Task task, long start) {
        final List<Target> targets;
        try {
            targets = compute(task);
        } catch (RuntimeException e) {
            failed(task, e);
            return;
        }
        final long took = System.nanoTime() - start;
        sample(relightSamples, took);
        if (took > SLOW_NANOS) {
            LOGGER.warn("The light recomputation of center {},{} took {} ms", task.center.x, task.center.z,
                    TimeUnit.NANOSECONDS.toMillis(took));
        }
        relights.increment();
        task.dropBlocks();
        task.computed = true;
        waiting.addAndGet(targets.size());
        installs.addAll(targets);
    }

    /** Waits while {@value #INSTALL_BACKLOG} target entries or more wait for the install. */
    private void awaitRoom() {
        synchronized (room) {
            while (waiting.get() >= INSTALL_BACKLOG) {
                try {
                    room.wait();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Tickets on the 5×5 around the center within {@code view_bounds}, whose chunks load if they are
     * not; then the blocks of each under its read-lock. A position past {@code view_bounds}, or one the
     * loader has nothing for and the instance filled with air of its own, is missing.
     */
    private void snapshot(Task task) {
        final int centerX = task.center.x, centerZ = task.center.z;
        final List<CompletableFuture<Chunk>> loads = new ArrayList<>(25);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                final int x = centerX + dx, z = centerZ + dz;
                if (!loader.inView(x, z)) {
                    loads.add(null);
                    continue;
                }
                instance.addChunkTicket(x, z, task);
                task.tickets.add(key(x, z));
                loads.add(instance.loadChunk(x, z));
            }
        }
        for (int i = 0; i < 25; i++) {
            final CompletableFuture<Chunk> load = loads.get(i);
            if (load == null) continue;
            if (!(load.join() instanceof SynxChunk chunk)) {
                task.taken[i] = new Taken(null, 0, null);
                continue;
            }
            chunk.lockReadLock();
            try {
                task.taken[i] = new Taken(chunk, chunk.blockChanges(), chunk.relightPalettes());
            } finally {
                chunk.unlockReadLock();
            }
        }
    }

    /**
     * The light of the 3×3 over the snapshot, compared target by target with what the target sends; a
     * handle target whose light changed is materialized for it here if its write-lock comes within
     * 50 ms, otherwise by the install.
     */
    private List<Target> compute(Task task) {
        final int centerX = task.center.x, centerZ = task.center.z;
        final LightResult result = LightEngine.relightArea(task, centerX, centerZ);
        final List<Target> targets = new ArrayList<>(9);
        for (int x = centerX - 1; x <= centerX + 1; x++) {
            for (int z = centerZ - 1; z <= centerZ + 1; z++) {
                final Taken taken = Objects.requireNonNull(task.at(x, z));
                final SynxChunk chunk = taken.chunk();
                if (chunk == null || !chunk.lightWritable()) {
                    targets.add(Target.notWritten(task, x, z));
                    continue;
                }
                final LightData current = chunk.currentLight();
                final byte[][] currentSky = SynxPacketDecoder.sky(current, sectionCount);
                final byte[][] currentBlock = SynxPacketDecoder.block(current, sectionCount);
                final byte[][] sky = new byte[sectionCount][];
                final byte[][] block = new byte[sectionCount][];
                boolean changed = false;
                for (int section = 0; section < sectionCount; section++) {
                    final byte[] newSky = result.sky(x, minSection + section, z);
                    if (!sameLight(newSky, currentSky[section])) {
                        sky[section] = newSky;
                        changed = true;
                    }
                    final byte[] newBlock = result.block(x, minSection + section, z);
                    if (!sameLight(newBlock, currentBlock[section])) {
                        block[section] = newBlock;
                        changed = true;
                    }
                }
                if (changed && chunk.materializeForLight(MATERIALIZE_WAIT)) lightMaterialized.increment();
                targets.add(new Target(task, x, z, chunk, sky, block, changed));
            }
        }
        return targets;
    }

    /** Null and empty arrays are all 0, as a missing section sends. */
    static boolean sameLight(byte @Nullable [] a, byte @Nullable [] b) {
        return Arrays.equals(a == null || a.length == 0 ? DARK : a, b == null || b.length == 0 ? DARK : b);
    }

    private void failed(Task task, RuntimeException e) {
        LOGGER.error("The light recomputation of center {},{} failed, its 3×3 keeps its old light", task.center.x,
                task.center.z, e);
        finish(task);
    }

    // ---- The install, at the end of the tick on the tick scheduling thread

    /**
     * Installs the results in the order they came, at most {@value #INSTALL_ENTRIES_PER_TICK} target
     * entries, and stops once it took 1 ms; the rest waits for the next tick. A target whose write-lock
     * the encode pool holds waits for the next tick as well, and the ones behind it with it.
     *
     * @return the target entries it handled: installed, found unchanged or skipped
     */
    public int installPhase() {
        if (!installing.tryLock()) return 0;
        final long start = System.nanoTime();
        int handled = 0;
        try {
            Target target;
            while (handled < INSTALL_ENTRIES_PER_TICK && (target = installs.peek()) != null) {
                if (!install(target)) break;
                installs.poll();
                handled++;
                if (target.task().left.decrementAndGet() == 0) finish(target.task());
                if (System.nanoTime() - start >= INSTALL_BUDGET_NANOS) break;
            }
        } finally {
            installing.unlock();
            if (handled > 0) {
                waiting.addAndGet(-handled);
                synchronized (room) {
                    room.notifyAll();
                }
            }
            installMaxNanos.accumulateAndGet(System.nanoTime() - start, Math::max);
        }
        if (draining) completeDrain();
        return handled;
    }

    /** @return false if the write-lock was not had: the target waits for the next tick */
    private boolean install(Target target) {
        final SynxChunk chunk = target.chunk();
        if (chunk == null || !valid(target)) {
            skipped.increment();
            return true;
        }
        final long now = instance.getWorldAge();
        if (target.changed()) {
            final SynxChunk.Installed installed = chunk.installLight(target.sky(), target.block());
            if (installed == null) return false;
            if (installed.materialized()) lightMaterialized.increment();
            final Set<Player> resent = resend(chunk, installed.light(), installed.swapped(), now);
            if (installed.swapped() && loader.options().resendLight()) {
                final LightData changed = changedSections(installed.sky(), installed.block());
                for (Player player : chunk.delivered().keySet()) {
                    if (!resent.contains(player) && online(player)) send(player, chunk, changed, now);
                }
            }
        } else {
            if (chunk.lightStale().containsValue(true)) {
                final LightData light = chunk.lightForResend();
                if (light == null) return false;
                resend(chunk, light, false, now);
            }
            chunk.lightStale().clear();
        }
        return true;
    }

    /** Whether the 3×3 around the target still holds the chunk objects and the {@code block_changes} of the snapshot. */
    private boolean valid(Target target) {
        for (int x = target.x() - 1; x <= target.x() + 1; x++) {
            for (int z = target.z() - 1; z <= target.z() + 1; z++) {
                final Taken taken = target.task().at(x, z);
                final SynxChunk then = taken == null ? null : taken.chunk();
                final SynxChunk now = instance.getChunk(x, z) instanceof SynxChunk chunk ? chunk : null;
                if (now != then) return false;
                if (then != null && then.blockChanges() != taken.blockChanges()) return false;
            }
        }
        return true;
    }

    /**
     * The full light to the players of {@code light_stale} still online and holding the chunk: all of
     * them if the light changed, the ones a resend is due to otherwise; then {@code light_stale} is empty.
     *
     * @return the players it was sent to
     */
    private Set<Player> resend(SynxChunk chunk, LightData light, boolean changed, long now) {
        final Set<Player> sent = new HashSet<>();
        for (Map.Entry<Player, Boolean> stale : chunk.lightStale().entrySet()) {
            final Player player = stale.getKey();
            if (!changed && !stale.getValue()) continue;
            if (!online(player) || !chunk.delivered().containsKey(player)) continue;
            send(player, chunk, light, now);
            sent.add(player);
        }
        chunk.lightStale().clear();
        return sent;
    }

    /** A light packet to a player; the chunk counts as delivered to them now (光照包刷新交付时刻). */
    private void send(Player player, SynxChunk chunk, LightData light, long now) {
        player.sendPacket(new UpdateLightPacket(chunk.chunkX(), chunk.chunkZ(), light));
        chunk.delivered().put(player, now);
        lightPackets.increment();
        lightSections.add(sections(light));
    }

    private boolean online(Player player) {
        return player.isOnline() && player.getInstance() == instance;
    }

    /** The sections an install swapped, and nothing else: bit i of either mask is section minSection − 1 + i. */
    private LightData changedSections(byte[] @Nullable [] sky, byte[] @Nullable [] block) {
        final BitSet skyMask = new BitSet(), blockMask = new BitSet(), emptySky = new BitSet(), emptyBlock = new BitSet();
        final List<byte[]> skyArrays = new ArrayList<>(), blockArrays = new ArrayList<>();
        for (int section = 0; section < sectionCount; section++) {
            add(sky[section], section + 1, skyMask, emptySky, skyArrays);
            add(block[section], section + 1, blockMask, emptyBlock, blockArrays);
        }
        return new LightData(skyMask, blockMask, emptySky, emptyBlock, skyArrays, blockArrays);
    }

    private static void add(byte @Nullable [] array, int bit, BitSet mask, BitSet empty, List<byte[]> arrays) {
        if (array == null) return;
        if (sameLight(array, null)) {
            empty.set(bit);
        } else {
            mask.set(bit);
            arrays.add(array);
        }
    }

    private int sections(LightData light) {
        final BitSet all = (BitSet) light.skyMask().clone();
        all.or(light.blockMask());
        all.or(light.emptySkyMask());
        all.or(light.emptyBlockMask());
        return all.get(1, sectionCount + 1).cardinality();
    }

    /**
     * The targets of a run are all handled, or it failed: its tickets go, and its center either runs
     * again, if a change came while it ran, or leaves, {@code relight_pending} of its 3×3 going down.
     */
    private void finish(Task task) {
        for (int i = 0; i < task.tickets.size(); i++) {
            final long key = task.tickets.getLong(i);
            instance.removeChunkTicket(CoordConversion.chunkIndexGetX(key), CoordConversion.chunkIndexGetZ(key), task);
        }
        final Center center = task.center;
        synchronized (lock) {
            if (center.again) {
                center.again = false;
                center.running = false;
                submit(center);
                return;
            }
            centers.remove(key(center.x, center.z));
            for (int x = center.x - 1; x <= center.x + 1; x++) {
                for (int z = center.z - 1; z <= center.z + 1; z++) {
                    pending.computeIfPresent(key(x, z), (_, count) -> count > 1 ? count - 1 : null);
                }
            }
        }
        sample(queueSamples, System.nanoTime() - center.firstChange);
    }

    // ---- The shutdown step

    /**
     * The shutdown step {@code relight}, shaped as {@code ShutdownSequence.Step#run} and registered
     * before {@code overlay}: waits at most min(500 ms, {@code remaining}) for every pending target to be
     * installed, so that the final flush writes the new light. With {@code tickAlive} the install at
     * the end of the tick does it; otherwise this thread installs. What is left at the deadline is
     * written with its old light, and a WARN counts it. Changes after the drain began are queued as
     * ever, with no promise to be installed before the final flush.
     *
     * @return completes once nothing is pending, or at the deadline
     */
    public CompletableFuture<?> drain(Duration remaining, boolean tickAlive) {
        final long budget = Math.min(DRAIN_CAP_NANOS, Math.max(0, remaining.toNanos()));
        final long now = System.nanoTime();
        if (drainStart == 0) drainStart = now;
        final long deadline = drainDeadline.accumulateAndGet(now + budget, Math::min);
        if (!tickAlive) {
            while (System.nanoTime() - deadline < 0 && pendingCenters() > 0) {
                if (installPhase() == 0) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
        }
        completeDrain();
        if (!drained.isDone()) {
            final long left = deadline - System.nanoTime();
            if (left <= 0) {
                timedOut();
            } else {
                draining = true;
                completeDrain();
                var _ = CompletableFuture.runAsync(this::timedOut, CompletableFuture.delayedExecutor(left, TimeUnit.NANOSECONDS));
            }
        }
        return drained;
    }

    private void completeDrain() {
        if (drained.isDone() || pendingCenters() > 0) return;
        if (drained.complete(null)) {
            LOGGER.info("Relight drain: {} ms, left=0", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - drainStart));
        }
    }

    private void timedOut() {
        if (drained.isDone()) return;
        final int left = targetsLeft();
        if (left == 0) {
            completeDrain();
            return;
        }
        if (drained.complete(null)) {
            LOGGER.warn("Relight drain did not finish within {} ms: {} targets left, the final flush writes them with "
                    + "their old light", TimeUnit.NANOSECONDS.toMillis(drainDeadline.get() - drainStart), left);
        }
    }

    /** The targets not installed yet: nine of a center not computed yet, the rest of one being installed. */
    private int targetsLeft() {
        synchronized (lock) {
            int left = 0;
            for (Center center : centers.values()) {
                final Task task = center.task;
                left += task == null || !task.computed ? 9 : task.left.get();
            }
            return left;
        }
    }

    // ---- Counters

    /** The counters; reading resets the two percentiles and the maximum. */
    public RelightStats stats() {
        return new RelightStats(relights.sum(), skipped.sum(), lightMaterialized.sum(), lightPackets.sum(),
                lightSections.sum(), p99(queueSamples) / 1e6, p99(relightSamples) / 1e6,
                installMaxNanos.getAndSet(0) / 1e6, pendingCenters());
    }

    private static void sample(LongArrayList samples, long nanos) {
        synchronized (samples) {
            if (samples.size() < WINDOW_SAMPLES) samples.add(nanos);
        }
    }

    private static double p99(LongArrayList samples) {
        final long[] values;
        synchronized (samples) {
            values = samples.toLongArray();
            samples.clear();
        }
        if (values.length == 0) return 0;
        Arrays.sort(values);
        return values[(int) Math.ceil(values.length * 0.99) - 1];
    }

    /** {@code relight_pending} of a chunk position. */
    int pending(int chunkX, int chunkZ) {
        return pending.getOrDefault(key(chunkX, chunkZ), 0);
    }

    /** The centers queued or running, without touching the window values of {@link #stats()}. */
    int pendingCenters() {
        synchronized (lock) {
            return centers.size();
        }
    }

    private static long key(int chunkX, int chunkZ) {
        return CoordConversion.chunkIndex(chunkX, chunkZ);
    }
}
