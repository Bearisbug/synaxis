package io.github.bearisbug.synaxis.app.shard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.adventure.EngineAdventure;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.event.player.PlayerBlockBreakEvent;
import io.github.bearisbug.synaxis.event.player.PlayerBlockPlaceEvent;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import io.github.bearisbug.synaxis.synx.SynxChunk;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.timer.Task;
import io.github.bearisbug.synaxis.world.DimensionType;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The world state flags of a shard in the directory mode ({@code docs/DESIGN-M3.md}
 * {@code API-M3-013}): read and replayed at start, applied at run time on the tick scheduling thread,
 * kept in step across the channels of the region through Redis and a read of every row each 60 s,
 * and the cells they govern protected from the players.
 * <p>
 * Every application of a value, whoever asked for it, ends on one thread: the startup thread for the
 * replay, the tick scheduling thread after it, where the version is checked again right before the
 * blocks change. That is the only place the applied versions and the governed cells are written, so
 * two applications of one key whose chunks load in the other order never take the flag back. The
 * blocks change inside {@link SynxChunk#asWorldFlag}, which a world that is not writable lets through.
 * <p>
 * A player decides how often the protection runs, so nothing here writes a log line of its own: the
 * warnings and errors all go through a {@link LogBudget}.
 */
public final class WorldFlagsImpl implements WorldFlags, AutoCloseable {

    /** The Redis channel of a region is this, the map and the region. */
    static final String CHANNEL_PREFIX = "synaxis:worldflag:";
    /** The code a start refused for flags that cannot be read leads with (§14). */
    static final String UNAVAILABLE = "synx-world-flags-unavailable";
    /** How often every flag of the region is read again, and the governed cells checked. */
    static final Duration SYNC_INTERVAL = Duration.ofSeconds(60);
    /** The governed cells the check after a read looks at in one tick. */
    static final int CHECKED_PER_TICK = 1_024;
    /** The failed reads in a row from which on each one is a warning: the 5th and every one after it. */
    static final int READ_FAILURES_WARNED = 5;
    /** The waits before the 3 retries of the read at start. */
    private static final List<Duration> RETRY_BACKOFF = List.of(Duration.ofSeconds(1), Duration.ofSeconds(2),
            Duration.ofSeconds(4));

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldFlagsImpl.class);

    /**
     * What the flags did, for {@code EVT:shard_started} and {@code EVT:chunk_stream_stats}.
     *
     * @param replayed     the keys applied by the replay at start
     * @param replayMillis how long reading and replaying them took together
     * @param conflicts    the cells found changed by another way than a flag, since the start
     */
    public record Stats(int replayed, long replayMillis, long conflicts) {
    }

    /** What a governed cell is to hold, and the key that governs it. */
    private record Target(String key, Block block) {
    }

    /**
     * One application of a value: the cells of the region it sets, the chunks they are in and how many
     * cells it left alone outside. It owns the tickets on those chunks, compared by identity, so two
     * applications of the same value hold a ticket each.
     */
    private static final class Application {
        final String key;
        final JsonElement value;
        final long version;
        final Map<BlockVec, Block> cells;
        final int outside;
        final long[] chunks;

        Application(String key, JsonElement value, long version, Map<BlockVec, Block> cells, int outside) {
            this.key = key;
            this.value = value;
            this.version = version;
            this.cells = cells;
            this.outside = outside;
            this.chunks = cells.keySet().stream().mapToLong(cell -> CoordConversion.chunkIndex(cell.chunkX(), cell.chunkZ()))
                    .distinct().toArray();
        }
    }

    private final WorldFlagStore store;
    private final RedisLink redis;
    private final String mapId;
    private final String regionId;
    private final SynxManifest.Bounds regionBounds;
    private final String channel;
    private final List<Duration> retryBackoff;
    private final Map<String, FlagApplier> appliers = new ConcurrentHashMap<>();
    // Written by the thread that applies (the startup thread, then the tick scheduling thread), read by any
    private final Map<String, Application> applied = new ConcurrentHashMap<>();
    private final Map<BlockVec, Target> governed = new ConcurrentHashMap<>();
    private final AtomicLong conflicts = new AtomicLong();
    private final AtomicInteger readFailures = new AtomicInteger();
    private final AtomicBoolean syncing = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LogBudget conflictLog = new LogBudget(LOGGER, "world flag conflicts");
    private final LogBudget outsideLog = new LogBudget(LOGGER, "world flag applications with cells outside the region");
    private final LogBudget overlapLog = new LogBudget(LOGGER, "world flag applications taking cells of another flag");
    private final LogBudget applierLog = new LogBudget(LOGGER, "failed world flag appliers");
    private final LogBudget syncLog = new LogBudget(LOGGER, "world flag sync failures");
    private final LogBudget replayLog = new LogBudget(LOGGER, "world flag replay warnings");
    // The startup thread only, before the process ticks
    private @Nullable List<WorldFlagStore.Row> loaded;
    private long loadNanos;
    private volatile boolean replayStarted;
    private volatile int replayedKeys;
    private volatile long replayMillis;
    private volatile @Nullable InstanceContainer instance;
    private volatile @Nullable Task syncTask;

    /**
     * Builds the flags of a region; reads nothing and starts no thread.
     *
     * @param dataSource   the player store database, which holds {@code world_flag}
     * @param redis        where the changes are announced
     * @param mapId        the {@code map_id} of the map
     * @param regionId     this shard's region
     * @param regionBounds the chunks of the region, the only ones a flag changes
     */
    public WorldFlagsImpl(DataSource dataSource, RedisLink redis, String mapId, String regionId,
                          SynxManifest.Bounds regionBounds) {
        this(dataSource, redis, mapId, regionId, regionBounds, RETRY_BACKOFF);
    }

    /** With other waits between the tries of the read at start, for the tests. */
    WorldFlagsImpl(DataSource dataSource, RedisLink redis, String mapId, String regionId,
                   SynxManifest.Bounds regionBounds, List<Duration> retryBackoff) {
        this.store = new WorldFlagStore(dataSource, mapId, regionId);
        this.redis = redis;
        this.mapId = mapId;
        this.regionId = regionId;
        this.regionBounds = regionBounds;
        this.channel = CHANNEL_PREFIX + mapId + ":" + regionId;
        this.retryBackoff = List.copyOf(retryBackoff);
    }

    @Override
    public void register(String key, FlagApplier applier) {
        if (replayStarted) throw new IllegalStateException("world flag " + key + " registered after the replay");
        appliers.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(applier, "applier"));
    }

    @Override
    public @Nullable FlagValue get(String key) {
        final Application last = applied.get(key);
        return last == null ? null : new FlagValue(last.value.deepCopy(), last.version);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Written, the value is handed to the application here and announced to the other channels: its chunks
     * load, then a later tick sets the blocks, so {@link #get} gives the new value only from that tick on.
     * A message that is lost is made up for by their next read of every flag.
     */
    @Override
    public CompletableFuture<FlagWrite> set(String key, JsonElement value, long expectedVersion) {
        final JsonElement copy = value.deepCopy();
        return CompletableFuture.supplyAsync(() -> write(key, copy, expectedVersion),
                task -> Thread.ofVirtual().name("Syn-WorldFlag-Write").start(task));
    }

    private FlagWrite write(String key, JsonElement value, long expectedVersion) {
        try {
            final boolean written = expectedVersion == 0 ? store.insert(key, value) : store.update(key, value, expectedVersion);
            if (!written) {
                final WorldFlagStore.Row current = store.read(key);
                return new FlagWrite.Conflict(current == null ? null : current.flagValue());
            }
        } catch (SQLException | RuntimeException e) {
            return new FlagWrite.Failed(e.toString());
        }
        final long version = expectedVersion + 1;
        apply(key, value, version);
        final JsonObject message = new JsonObject();
        message.addProperty("flag_key", key);
        message.addProperty("version", version);
        // False while Redis is down: the other channels' next read of every flag brings the value
        redis.publishWorldFlag(channel, message.toString());
        return new FlagWrite.Written(version);
    }

    /**
     * Reads every flag of the region, on the calling thread: at the start of {@link ShardWorld#start},
     * before the overlay store is opened. Retried 3 times, after 1, 2 and 4 s.
     *
     * @throws IllegalArgumentException starting with {@code synx-world-flags-unavailable} when the
     *                                  flags cannot be read, which refuses the start
     */
    public void load() {
        final long started = System.nanoTime();
        int retries = 0;
        while (true) {
            try {
                this.loaded = store.readAll();
                this.loadNanos = System.nanoTime() - started;
                return;
            } catch (SQLException e) {
                if (retries == retryBackoff.size()) {
                    throw new IllegalArgumentException(UNAVAILABLE + ": the world flags of map " + mapId + " region "
                            + regionId + " could not be read in " + (retries + 1) + " tries: " + e.getMessage(), e);
                }
                pause(retryBackoff.get(retries++));
            }
        }
    }

    private static void pause(Duration backoff) {
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException(UNAVAILABLE + ": interrupted before reading the world flags again", e);
        }
    }

    /**
     * Applies what {@link #load} read to the map instance, on the calling thread, before the process
     * ticks: after the instance is built, before the world border, the spawn point and the bind.
     * Reads no database and refuses nothing. A key without an applier keeps the blocks the map and the
     * overlay give it; one whose applier or block handlers throw, or whose chunks do not load, may keep
     * some of them, and is applied again by the first read of every flag after {@link #start}. No
     * conflict is counted: there is no earlier value.
     *
     * @param instance the map instance
     */
    public void replay(InstanceContainer instance) {
        final List<WorldFlagStore.Row> rows = this.loaded;
        if (rows == null) throw new IllegalStateException("the world flags are replayed before they are read");
        final long started = System.nanoTime();
        this.replayStarted = true;
        this.instance = instance;
        final List<String> unregistered = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        final List<String> outside = new ArrayList<>();
        final List<String> taken = new ArrayList<>();
        RuntimeException failure = null;
        int count = 0;
        for (WorldFlagStore.Row row : rows) {
            final FlagApplier applier = appliers.get(row.key());
            if (applier == null) {
                unregistered.add(row.key());
                continue;
            }
            try {
                final Application application = application(instance, row.key(), row.value(), row.version(), applier);
                if (application.outside > 0) outside.add(row.key() + " " + application.outside + " cells outside");
                // No ticket: chunks unload once a tick, and the process does not tick yet
                loadChunks(instance, application);
                setBlocks(instance, application);
                taken.addAll(record(application));
                count++;
            } catch (RuntimeException e) {
                failed.add(row.key());
                if (failure == null) failure = e;
            }
        }
        if (!unregistered.isEmpty()) {
            replayLog.warn("World flags without an applier keep the blocks of the map and the overlay: {}", unregistered);
        }
        if (!outside.isEmpty()) {
            outsideLog.warn("Replayed world flags ask for cells outside region {} or the world height, they are left "
                    + "alone: {}", regionId, outside);
        }
        warnTaken(taken);
        if (failure != null) {
            applierLog.error(failure, "The world flags {} failed in the replay, the first read of every flag after the "
                    + "start applies them again", failed);
        }
        this.loaded = null;
        this.replayedKeys = count;
        this.replayMillis = TimeUnit.NANOSECONDS.toMillis(loadNanos + System.nanoTime() - started);
    }

    /**
     * Protects the governed cells from the players, subscribes to the region's Redis channel and
     * schedules the read of every flag, on the next tick and then each 60 s; step (5) of {@code Main}.
     *
     * @param instance the map instance
     */
    public void start(InstanceContainer instance) {
        this.instance = instance;
        instance.eventNode()
                .addListener(PlayerBlockBreakEvent.class, event -> {
                    if (governed.containsKey(event.getBlockPosition())) event.setCancelled(true);
                })
                .addListener(PlayerBlockPlaceEvent.class, event -> {
                    if (governed.containsKey(event.getBlockPosition())) event.setCancelled(true);
                });
        redis.listenWorldFlags(channel, this::received);
        // A first read on the next tick: what another channel wrote after the read at start and before
        // the subscription is neither in the replay nor in a message
        this.syncTask = instance.process().scheduler().buildTask(this::syncInBackground).repeat(SYNC_INTERVAL).schedule();
    }

    /** Returns what the flags did so far. */
    public Stats stats() {
        return new Stats(replayedKeys, replayMillis, conflicts.get());
    }

    /**
     * A message of the region's Redis channel: the key is read again if it has an applier here and its
     * version is new here.
     */
    private void received(String message) {
        if (closed.get()) return;
        final String key;
        final long version;
        try {
            final JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            key = json.get("flag_key").getAsString();
            version = json.get("version").getAsLong();
        } catch (RuntimeException e) {
            syncLog.warn(e, "Ignoring a malformed world flag message on {}", channel);
            return;
        }
        if (!appliers.containsKey(key) || version <= appliedVersion(key)) return;
        Thread.ofVirtual().name("Syn-WorldFlag-Read").start(() -> {
            final WorldFlagStore.Row row;
            try {
                row = store.read(key);
            } catch (SQLException e) {
                readFailed(e);
                return;
            }
            readFailures.set(0);
            if (row != null) apply(row.key(), row.value(), row.version());
        });
    }

    private void syncInBackground() {
        if (closed.get() || !syncing.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("Syn-WorldFlag-Sync").start(() -> {
            try {
                sync();
            } finally {
                syncing.set(false);
            }
        });
    }

    /** Runs a round of the 60 s read at once, on the calling thread, for the tests. */
    void syncNow() {
        sync();
    }

    /** Whether the read every 60 s is still scheduled, for the tests. */
    boolean syncScheduled() {
        final Task task = this.syncTask;
        return task != null && task.isAlive();
    }

    /**
     * The read every 60 s: applies every flag whose version is new here, then checks the governed
     * cells of the loaded chunks from the next tick on, chunk by chunk.
     */
    private void sync() {
        final List<WorldFlagStore.Row> rows;
        try {
            rows = store.readAll();
        } catch (SQLException e) {
            readFailed(e);
            return;
        }
        readFailures.set(0);
        for (WorldFlagStore.Row row : rows) apply(row.key(), row.value(), row.version());
        final InstanceContainer instance = this.instance;
        // By chunk, sorted here and not on the tick: the cells a tick checks are in as few chunks as can be,
        // each of which may have its segment ① decoded to read a block
        final List<BlockVec> cells = governed.keySet().stream()
                .sorted(Comparator.comparingInt(BlockVec::chunkX).thenComparingInt(BlockVec::chunkZ)).toList();
        if (instance == null || cells.isEmpty() || closed.get()) return;
        instance.process().scheduler().scheduleNextTick(() -> check(instance, cells, 0));
    }

    private void readFailed(SQLException e) {
        final int failures = readFailures.incrementAndGet();
        if (failures >= READ_FAILURES_WARNED) {
            syncLog.warn("The world flags of map {} region {} were not read {} times in a row: {}", mapId, regionId,
                    failures, e.toString());
        }
    }

    /**
     * Sets the governed cells of loaded chunks that hold something else ({@link SynxChunk#sameBlock}) back
     * to their target, at most {@value #CHECKED_PER_TICK} a tick, each a conflict. On the tick scheduling
     * thread; a block handler that throws ends this tick's cells, not the ticks after it.
     */
    private void check(InstanceContainer instance, List<BlockVec> cells, int from) {
        if (closed.get()) return;
        final int to = Math.min(cells.size(), from + CHECKED_PER_TICK);
        try {
            SynxChunk.asWorldFlag(() -> {
                for (int i = from; i < to; i++) {
                    final BlockVec cell = cells.get(i);
                    final Target target = governed.get(cell);
                    // What a cell is held to is also what was applied to it last
                    if (target != null) setCell(instance, target.key(), cell, target.block(), target.block());
                }
            });
        } finally {
            if (to < cells.size()) instance.process().scheduler().scheduleNextTick(() -> check(instance, cells, to));
        }
    }

    /**
     * Applies a value whose version is new here: takes a ticket on each of its chunks, loads them on a
     * virtual thread, then hands the application to the next tick. Any thread. Does nothing, and logs
     * nothing, before the replay (the first read of every flag after it brings the value), for a key
     * without an applier here, once closed, or for a version not newer than the applied one.
     */
    private void apply(String key, JsonElement value, long version) {
        final InstanceContainer instance = this.instance;
        final FlagApplier applier = appliers.get(key);
        if (instance == null || applier == null || closed.get() || version <= appliedVersion(key)) return;
        final Application application;
        try {
            application = application(instance, key, value, version, applier);
        } catch (RuntimeException e) {
            applierLog.error(e, "The applier of world flag {} failed on version {}, the flag stays at version {}", key,
                    version, appliedVersion(key));
            return;
        }
        warnOutside(application);
        hold(instance, application);
        Thread.ofVirtual().name("Syn-WorldFlag-Load").start(() -> {
            try {
                loadChunks(instance, application);
            } catch (RuntimeException e) {
                release(instance, application);
                syncLog.warn(e, "The chunks of world flag {} version {} did not load, the next read tries again", key,
                        version);
                return;
            }
            instance.process().scheduler().scheduleNextTick(() -> execute(instance, application));
        });
    }

    /**
     * {@link #apply} with the chunks loaded on the calling thread, so that applications reach the
     * next tick in the order of the calls whatever their versions, for the tests.
     *
     * @param key     the flag key, registered
     * @param value   the value to apply
     * @param version its version
     */
    void schedule(String key, JsonElement value, long version) {
        final InstanceContainer instance = Objects.requireNonNull(this.instance, "the flags are not replayed");
        final Application application = application(instance, key, value, version,
                Objects.requireNonNull(appliers.get(key), key));
        warnOutside(application);
        hold(instance, application);
        loadChunks(instance, application);
        instance.process().scheduler().scheduleNextTick(() -> execute(instance, application));
    }

    /**
     * On the tick scheduling thread, before the tick threads start: only a version newer than the
     * applied one changes blocks, any other gives its tickets back and nothing else.
     */
    private void execute(InstanceContainer instance, Application application) {
        final boolean newer = !closed.get() && application.version > appliedVersion(application.key);
        try {
            if (newer) setBlocks(instance, application);
        } finally {
            release(instance, application);
        }
        if (newer) warnTaken(record(application));
    }

    /**
     * The cells whose block differs from their target ({@link SynxChunk#sameBlock}), set to it. A cell
     * that holds neither the new target nor the one applied last was changed another way: a conflict.
     */
    private void setBlocks(InstanceContainer instance, Application application) {
        final Application last = applied.get(application.key);
        SynxChunk.asWorldFlag(() -> application.cells.forEach((cell, target) ->
                setCell(instance, application.key, cell, target, last == null ? null : last.cells.get(cell))));
    }

    /**
     * Sets a cell of a loaded chunk that holds something else than {@code target} to it: a conflict if
     * it held neither that nor {@code last}, the target applied to it last. Inside
     * {@link SynxChunk#asWorldFlag}.
     */
    private void setCell(InstanceContainer instance, String key, BlockVec cell, Block target, @Nullable Block last) {
        final Chunk chunk = instance.getChunkAt(cell);
        // Not loaded, or failed: inside asWorldFlag the only read only chunks of the region are failed ones, while
        // outside it a world that is not writable makes them all read only, so this test has to stay inside
        if (chunk == null || chunk.isReadOnly()) return;
        final Block now = instance.getBlock(cell);
        if (SynxChunk.sameBlock(now, target)) return;
        if (last != null && !SynxChunk.sameBlock(now, last)) conflict(key, cell, now, target);
        instance.setBlock(cell, target);
    }

    /**
     * The applied version of the key and the cells it governs, after its blocks are set. A cell another
     * key governed goes to this one, the key applied last.
     *
     * @return what it took from the other keys, one {@code <key> over <other key> <n> cells} for each
     */
    private List<String> record(Application application) {
        final Application previous = applied.put(application.key, application);
        if (previous != null) {
            for (BlockVec cell : previous.cells.keySet()) {
                final Target target = governed.get(cell);
                if (target != null && target.key().equals(application.key)) governed.remove(cell);
            }
        }
        final Map<String, Integer> taken = new TreeMap<>();
        application.cells.forEach((cell, block) -> {
            final Target replaced = governed.put(cell, new Target(application.key, block));
            if (replaced != null && !replaced.key().equals(application.key)) taken.merge(replaced.key(), 1, Integer::sum);
        });
        return taken.entrySet().stream()
                .map(entry -> application.key + " over " + entry.getKey() + " " + entry.getValue() + " cells").toList();
    }

    /**
     * One line for the cells taken from other keys: two keys must not give the same cell, the earlier key
     * would count it a conflict on its next value.
     */
    private void warnTaken(List<String> taken) {
        if (!taken.isEmpty()) overlapLog.warn("World flags give the same cells, the one applied last holds them: {}", taken);
    }

    /** The cells of a value inside the region and the world height; the others are left alone. */
    private Application application(InstanceContainer instance, String key, JsonElement value, long version,
                                    FlagApplier applier) {
        final List<FlagCell> cells = applier.cells(value);
        final DimensionType dimension = instance.getCachedDimensionType();
        final Map<BlockVec, Block> inside = new LinkedHashMap<>();
        int outside = 0;
        for (FlagCell cell : cells) {
            final int chunkX = CoordConversion.globalToChunk(cell.x());
            final int chunkZ = CoordConversion.globalToChunk(cell.z());
            if (!regionBounds.contains(chunkX, chunkZ) || cell.y() < dimension.minY() || cell.y() >= dimension.maxY()) {
                outside++;
                continue;
            }
            inside.put(new BlockVec(cell.x(), cell.y(), cell.z()), cell.block());
        }
        return new Application(key, value, version, inside, outside);
    }

    /** One line for an application at run time with cells outside; the replay writes one for all its keys. */
    private void warnOutside(Application application) {
        if (application.outside > 0) {
            outsideLog.warn("World flag {} version {} asks for {} cells outside region {} or the world height, they are "
                    + "left alone", application.key, application.version, application.outside, regionId);
        }
    }

    private void conflict(String key, BlockVec cell, Block found, Block target) {
        conflicts.incrementAndGet();
        conflictLog.warn("World flag {}: the cell {} {} {} held {}, changed another way, set to {}", key, cell.blockX(),
                cell.blockY(), cell.blockZ(), describe(found), describe(target));
    }

    /** The state with its properties, then the NBT as SNBT and the handler namespace of a block that has them. */
    private static String describe(Block block) {
        final StringBuilder text = new StringBuilder(block.state());
        final CompoundBinaryTag nbt = block.nbt();
        // An empty compound is no NBT, as for SynxChunk#sameBlock
        if (nbt != null && nbt.size() > 0) text.append(' ').append(snbt(nbt));
        final BlockHandler handler = block.handler();
        if (handler != null) text.append(" handler ").append(handler.getKey().asString());
        return text.toString();
    }

    private static String snbt(CompoundBinaryTag nbt) {
        try {
            return EngineAdventure.tagStringIO().asString(nbt);
        } catch (IOException _) {
            return nbt.toString();
        }
    }

    private long appliedVersion(String key) {
        final Application last = applied.get(key);
        return last == null ? 0 : last.version;
    }

    private static void hold(InstanceContainer instance, Application application) {
        for (long chunk : application.chunks) {
            instance.addChunkTicket(CoordConversion.chunkIndexGetX(chunk), CoordConversion.chunkIndexGetZ(chunk), application);
        }
    }

    private static void release(InstanceContainer instance, Application application) {
        for (long chunk : application.chunks) {
            instance.removeChunkTicket(CoordConversion.chunkIndexGetX(chunk), CoordConversion.chunkIndexGetZ(chunk),
                    application);
        }
    }

    private static void loadChunks(InstanceContainer instance, Application application) {
        final CompletableFuture<?>[] loads = new CompletableFuture<?>[application.chunks.length];
        for (int i = 0; i < loads.length; i++) {
            final long chunk = application.chunks[i];
            loads[i] = instance.loadChunk(CoordConversion.chunkIndexGetX(chunk), CoordConversion.chunkIndexGetZ(chunk));
        }
        CompletableFuture.allOf(loads).join();
    }

    /**
     * Stops the 60 s read; from then on nothing is applied, neither what the region's Redis channel still
     * brings, nor what a read finds, nor an application already queued, and no governed cell is checked.
     * Runs before the player store closes its pool and its Redis link, which ends the subscription.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        final Task task = this.syncTask;
        if (task != null) task.cancel();
    }
}
