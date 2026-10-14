package io.github.bearisbug.synaxis.game.client.farview.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.factories.IDhApiWrapperFactory;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import io.github.bearisbug.synaxis.game.client.farview.FarViewCache;
import io.github.bearisbug.synaxis.game.client.farview.FarViewClient;
import io.github.bearisbug.synaxis.game.client.farview.FarViewColumns;
import io.github.bearisbug.synaxis.game.client.farview.FarViewConnection;
import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Sample;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bake of {@code docs/DESIGN-M3.md} {@code API-M3-015} (写库条, §11.3 {@code baking}): on a platform
 * thread of its own, the packs of an announcement become data sources at section detail level
 * {@code 6 + b}, handed to the running DH level with {@code applyToParent} set; then the rows are
 * counted on DH's own connection, the parent propagation waited for, and only then the completion
 * marker written. A library whose marker matched is counted first ({@code 载入核对}); only when the
 * count misses is it downloaded as needed and baked again.
 */
final class DhLibraryBaker implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger("synaxis");

    /** Data sources handed to DH and not yet completed, at most. */
    static final int IN_FLIGHT = 16;
    /** How often a wait wakes up to look at the cancel flag. */
    static final long POLL_MILLIS = 250;
    /** How long the parent propagation may take once every row is written. */
    static final long PARENTS_MILLIS = 120_000;

    private static final List<String> DATABASE = List.of("DistantHorizons.sqlite", "DistantHorizons.sqlite-wal",
            "DistantHorizons.sqlite-shm");

    private final FarViewConnection connection;
    private final IDhApiLevelWrapper level;
    private final boolean loadCheck;

    private DhLibraryBaker(FarViewConnection connection, IDhApiLevelWrapper level, boolean loadCheck) {
        this.connection = connection;
        this.level = level;
        this.loadCheck = loadCheck;
    }

    /**
     * Starts the bake of a connection, the {@link FarViewConnection.Baker} of the DH side.
     *
     * @param connection the connection
     * @param level      the {@link IDhApiLevelWrapper} DH loaded
     * @param loadCheck  whether the marker matched, so the rows are counted before anything is baked
     */
    static void start(FarViewConnection connection, Object level, boolean loadCheck) {
        final Thread thread = Thread.ofPlatform().name("synaxis-farview-bake").daemon()
                .unstarted(new DhLibraryBaker(connection, (IDhApiLevelWrapper) level, loadCheck));
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        DhTestHook.bakeThread = thread;
        thread.start();
    }

    @Override
    public void run() {
        final String key = connection.viewKey();
        try {
            await(DhTestHook.holdStart);
            final long started = System.nanoTime();
            final IDhLevel dhLevel = ((ILevelWrapper) level).getDhLevel();
            final FullDataSourceV2Repo repo = dhLevel.getFullDataProvider().repo;
            final FarViewAnnouncement announcement = connection.announcement();
            final FarViewCache cache = connection.cache();
            if (loadCheck) {
                if (!connection.rebakeAfterLoadCheck(rowsAt(repo, announcement.baseLevel()))) return;
                final List<FarViewAnnouncement.Tile> missing = cache.missing(announcement.tiles());
                if (!missing.isEmpty() && !connection.download(missing)) return;
            }
            if (!connection.baking()) return;
            try {
                bake(dhLevel, repo, announcement, cache, key, started);
            } finally {
                // Every bake that ran holds the cache to its limit, a failed one too, so a client whose bakes keep
                // failing does not grow it without end; this connection has nothing downloading by now (缓存条)
                if (!stopped()) clean(cache, key);
            }
        } catch (Exception | LinkageError e) {
            if (stopped()) return;
            connection.state(FarViewConnection.State.ABSENT);
            connection.failure("bake failed");
            LOGGER.warn("{}view {} bake failed", FarViewClient.PREFIX, key, e);
        } finally {
            // An Error (OutOfMemoryError, StackOverflowError) skips the catch: the state never stays baking
            if (connection.state() == FarViewConnection.State.BAKING) connection.state(FarViewConnection.State.ABSENT);
            // The thread, its task and the level wrapper that holds the client level need not outlive the bake
            if (DhTestHook.bakeThread == Thread.currentThread()) DhTestHook.bakeThread = null;
            connection.settle();
        }
    }

    /** A data source handed to DH and the future of its write. */
    private record InFlight(CompletableFuture<Void> future, FullDataSourceV2 source) {
    }

    private void bake(IDhLevel dhLevel, FullDataSourceV2Repo repo, FarViewAnnouncement announcement, FarViewCache cache,
                      String key, long started) throws IOException, InterruptedException {
        final int baseLevel = announcement.baseLevel();
        // The world height: DH 3.3.2 deprecates getHeight() for getMaxHeight(), which on a client level is ClientLevel#getHeight
        final int height = level.getMaxHeight();

        // 1. Every pack against the level, before anything is written; only its block count is kept, so here and in
        // step 2 at most one pack (≤ 40 MiB) is on the heap, however large the announcement
        long fineBlocks = 0;
        long coarseBlocks = 0;
        for (FarViewAnnouncement.Tile tile : announcement.tiles()) {
            final FarViewPack.Reader reader = read(cache, tile);
            if (reader == null) return;
            final FarViewPack.Header header = reader.header();
            if (header.minY() != level.getMinHeight() || header.height() != height
                    || header.blockStates() != Block.BLOCK_STATE_REGISTRY.size()) {
                LOGGER.warn("{}Far view off: pack {} does not match the level (min_y {} height {} block_states {}; the level {}, {}, {})",
                        FarViewClient.PREFIX, FarViewCache.hex(tile).substring(0, 12), header.minY(), header.height(),
                        header.blockStates(), level.getMinHeight(), height, Block.BLOCK_STATE_REGISTRY.size());
                connection.state(FarViewConnection.State.ABSENT);
                connection.failure("pack does not match the level");
                return;
            }
            if (whole(tile)) fineBlocks += reader.blocks(FarViewPack.Layer.FINE).size();
            else coarseBlocks += reader.blocks(FarViewPack.Layer.COARSE).size();
        }

        // 2. and 3. The whole tiles' fine layers first, then the coarse tiles, in announcement order
        final boolean expand = DhTestHook.expandCoarse;
        final int perCoarseBlock = expand ? FarViewColumns.expansion(baseLevel) * FarViewColumns.expansion(baseLevel) : 1;
        final long sources = fineBlocks + coarseBlocks * perCoarseBlock;
        // Unexpanded (v1-coarse-only), a coarse source sits at the coarse level, not among the rows at b
        final long rowsAtBase = expand ? sources : fineBlocks;
        connection.progress(0, sources);
        if (!handOver(dhLevel, announcement, cache, height, expand, sources)) return;

        // 4. A completed future says nothing about the library: count its rows, then wait for the parents
        if (DhTestHook.writeCheck && !rowsWritten(repo, key, baseLevel, rowsAtBase)) return;
        final long writeMillis = FarViewConnection.millisSince(started);
        if (DhTestHook.writeCheck && !parentsDone(repo, key)) return;
        if (stopped()) return;

        // 5. Complete
        complete(announcement, cache, key, sources, started, writeMillis);
    }

    /**
     * Steps 2 and 3: every data source made and handed to DH, at most {@value #IN_FLIGHT} in flight, the
     * cancel flag checked before each.
     *
     * @return whether every one completed; false when the bake is to stop
     */
    private boolean handOver(IDhLevel dhLevel, FarViewAnnouncement announcement, FarViewCache cache, int height,
                             boolean expand, long sources) throws IOException, InterruptedException {
        final int baseLevel = announcement.baseLevel();
        // Whole tiles first, each group in announcement order: List.sort is stable
        final List<FarViewAnnouncement.Tile> tiles = new ArrayList<>(announcement.tiles());
        tiles.sort(Comparator.comparing((FarViewAnnouncement.Tile tile) -> !whole(tile)));
        final Wrappers wrappers = new Wrappers(level);
        final Deque<InFlight> inFlight = new ArrayDeque<>();
        long handed = 0;
        for (FarViewAnnouncement.Tile tile : tiles) {
            // Read again, one pack at a time (step 1)
            final FarViewPack.Reader reader = read(cache, tile);
            if (reader == null) return false;
            final boolean wholeTile = whole(tile);
            final FarViewPack.Layer layer = wholeTile ? FarViewPack.Layer.FINE : FarViewPack.Layer.COARSE;
            for (FarViewPack.Block block : reader.blocks(layer)) {
                final List<Sample> samples = samples(cache, tile, reader, block);
                if (samples == null) return false;
                final List<FarViewColumns.Source> made = wholeTile
                        ? List.of(FarViewColumns.fine(tile.tileX(), tile.tileZ(), baseLevel, block.bx(), block.bz(), samples))
                        : expand ? FarViewColumns.coarse(tile.tileX(), tile.tileZ(), baseLevel, block.bx(), block.bz(), samples)
                        : List.of(FarViewColumns.coarseUnexpanded(tile.tileX(), tile.tileZ(), block.bx(), block.bz(), samples));
                for (FarViewColumns.Source source : made) {
                    if (stopped()) return false;
                    final FullDataSourceV2 dataSource = build(source, reader.biomes(), height, wrappers);
                    if (handed == sources - 1 && DhTestHook.holdBeforeLast != null && !holdBeforeLast(inFlight)) return false;
                    inFlight.addLast(new InFlight(dhLevel.updateDataSourcesAsync(dataSource), dataSource));
                    connection.progress(++handed, sources);
                    if (!completeUntil(inFlight, IN_FLIGHT - 1)) return false;
                }
            }
        }
        return completeUntil(inFlight, 0);
    }

    /**
     * Step 4, the rows: DH's rows at b against the sources counted there. No answer or a short count on a
     * connection that ended stops the bake without a WARN line: DH closes the library as the connection or
     * the level ends, so that says nothing about the library.
     *
     * @return whether the rows are all there; false when the bake is to stop
     */
    private boolean rowsWritten(FullDataSourceV2Repo repo, String key, int baseLevel, long rowsAtBase) {
        final Long rows = rowsAt(repo, baseLevel);
        if (rows != null && rows == rowsAtBase) return true;
        if (stopped()) return false;
        LOGGER.warn("{}view {} incomplete: {} of {} sources in the library", FarViewClient.PREFIX, key,
                rows == null ? "no answer, so none" : rows, rowsAtBase);
        connection.state(FarViewConnection.State.ABSENT);
        connection.failure("incomplete");
        return false;
    }

    /**
     * Step 4, the parents: waits until DH propagated every row up, at most {@value #PARENTS_MILLIS} ms;
     * a connection that ended stops it without a WARN line, as {@link #rowsWritten} does.
     *
     * @return whether no row waits for its parents; false when the bake is to stop
     */
    private boolean parentsDone(FullDataSourceV2Repo repo, String key) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PARENTS_MILLIS);
        Long pending;
        while ((pending = count(repo, "ApplyToParent = 1")) != null && pending > 0 && System.nanoTime() < deadline) {
            if (stopped()) return false;
            Thread.sleep(POLL_MILLIS);
        }
        if (pending != null && pending <= 0) return true;
        if (stopped()) return false;
        LOGGER.warn("{}view {} parents pending: {}", FarViewClient.PREFIX, key, pending == null ? "no answer" : pending);
        connection.state(FarViewConnection.State.ABSENT);
        connection.failure("parents pending");
        return false;
    }

    /** Step 5: the marker, the readings and the bake line; the library is ready. */
    private void complete(FarViewAnnouncement announcement, FarViewCache cache, String key, long sources, long started,
                          long writeMillis) throws IOException {
        final long millis = FarViewConnection.millisSince(started);
        final long visible = FarViewConnection.millisSince(connection.announcedNanos());
        final long now = System.currentTimeMillis();
        cache.writeMarker(new FarViewCache.Marker(FarViewCache.MARKER_FORMAT, key, FarViewClient.DH_VERSION,
                FarViewClient.BAKE_CODE_VERSION, announcement.tiles().stream().map(FarViewCache.MarkerTile::of).toList(),
                sources, millis, now, now));
        final long databaseBytes = databaseBytes(cache.library(key));
        connection.timings(new FarViewConnection.Timings(millis, writeMillis, visible, databaseBytes, sources,
                FarViewConnection.millisSince(connection.levelLoadedNanos())));
        connection.state(FarViewConnection.State.READY);
        LOGGER.info("{}view {} baked in {} ms: {} sources, {} B; written {} ms, visible {} ms after the announcement",
                FarViewClient.PREFIX, key, millis, sources, databaseBytes, writeMillis, visible);
    }

    /** The cache held to its limit after a bake ({@code API-M3-015} 缓存条). */
    private static void clean(FarViewCache cache, String key) {
        try {
            final FarViewCache.Cleaned cleaned = cache.clean(FarViewCache.LIMIT_BYTES, key);
            if (!cleaned.libraries().isEmpty() || !cleaned.packs().isEmpty()) {
                LOGGER.info("{}cache cleaned: libraries {}, {} object(s), {} B left", FarViewClient.PREFIX, cleaned.libraries(),
                        cleaned.packs().size(), cleaned.size());
            }
        } catch (IOException e) {
            LOGGER.warn("{}cache cleaning skipped: {}", FarViewClient.PREFIX, e.toString());
        }
    }

    /**
     * One pack off the cache. One that cannot be read or parsed is removed, so the next connection downloads
     * it again ({@code API-M3-015} 写库条第 1 步): {@code packs/} knows an object by its name alone.
     *
     * @return the pack, null when it could not be read and the bake is to stop
     */
    private FarViewPack.@Nullable Reader read(FarViewCache cache, FarViewAnnouncement.Tile tile) throws IOException {
        try {
            return FarViewPack.Reader.read(Files.readAllBytes(cache.pack(FarViewCache.hex(tile))));
        } catch (IOException | FarViewPack.Invalid e) {
            damaged(cache, tile, e);
            return null;
        }
    }

    /**
     * The samples of one block of a pack; a block that does not inflate is a damaged pack, removed as
     * {@link #read} removes one.
     *
     * @return the samples, null when the bake is to stop
     */
    private @Nullable List<Sample> samples(FarViewCache cache, FarViewAnnouncement.Tile tile, FarViewPack.Reader reader,
                                           FarViewPack.Block block) throws IOException {
        try {
            return reader.samples(block);
        } catch (FarViewPack.Invalid e) {
            damaged(cache, tile, e);
            return null;
        }
    }

    /** Removes a pack the cache holds and the bake cannot read, and takes this connection's far view off. */
    private void damaged(FarViewCache cache, FarViewAnnouncement.Tile tile, Exception e) throws IOException {
        final String hex = FarViewCache.hex(tile);
        // Logged before the delete: a pack another process holds fails the read and the delete alike, and the
        // delete's IOException then fails the bake in run(), which would leave the read cause out of the log
        LOGGER.warn("{}Far view off: pack {} in the cache cannot be read, removed: {}", FarViewClient.PREFIX,
                hex.substring(0, 12), e.toString());
        connection.state(FarViewConnection.State.ABSENT);
        connection.failure("pack damaged");
        Files.deleteIfExists(cache.pack(hex));
    }

    private static boolean whole(FarViewAnnouncement.Tile tile) {
        return tile.layer() == FarViewAnnouncement.LAYER_FULL;
    }

    private boolean stopped() {
        return connection.cancelled() && !DhTestHook.ignoreCancel;
    }

    /**
     * The gametests' hold before the last source ({@link DhTestHook#holdBeforeLast}), every earlier one
     * completed first.
     *
     * @return false when the bake is to stop
     */
    private boolean holdBeforeLast(Deque<InFlight> inFlight) throws InterruptedException {
        if (!completeUntil(inFlight, 0)) return false;
        final CountDownLatch reached = DhTestHook.reachedLast;
        if (reached != null) reached.countDown();
        await(DhTestHook.holdBeforeLast);
        return true;
    }

    /**
     * Waits for the oldest writes until at most {@code most} are in flight, waking every
     * {@value #POLL_MILLIS} ms for the cancel flag.
     *
     * @param inFlight the writes in flight, oldest first
     * @param most     how many may stay
     * @return true once at most {@code most} are left, the source of each one that completed (or failed)
     *         closed and removed; false when the bake stopped while waiting, the oldest left where it is
     */
    private boolean completeUntil(Deque<InFlight> inFlight, int most) throws InterruptedException {
        while (inFlight.size() > most) {
            final InFlight oldest = inFlight.getFirst();
            while (true) {
                try {
                    oldest.future().get(POLL_MILLIS, TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException _) {
                    if (stopped()) return false;
                } catch (ExecutionException e) {
                    // DH logs its own failures; the row count after the last one is what decides
                    LOGGER.debug("{}a write of view {} failed: {}", FarViewClient.PREFIX, connection.viewKey(), e.getCause());
                    break;
                }
            }
            inFlight.removeFirst();
            // DH closes the copy it took out of its library, never the source it was handed (FullDataUpdaterV2)
            oldest.source().close();
        }
        return true;
    }

    private static void await(@Nullable CountDownLatch latch) throws InterruptedException {
        if (latch != null) latch.await();
    }

    private static FullDataSourceV2 build(FarViewColumns.Source source, List<String> biomes, int height, Wrappers wrappers) {
        final FarViewColumns.Position position = source.position();
        final FullDataSourceV2 dataSource = FullDataSourceV2.createEmpty(
                DhSectionPos.encode((byte) position.sectionDetail(), position.x(), position.z()));
        if (DhTestHook.applyToParent) dataSource.applyToParent = Boolean.TRUE;
        // Neighbouring columns of an expanded coarse block are one sample; their points are built once
        final Map<Sample, List<DhApiTerrainDataPoint>> built = new IdentityHashMap<>();
        final List<Sample> columns = source.columns();
        for (int z = 0; z < FarViewColumns.WIDTH; z++) {
            for (int x = 0; x < FarViewColumns.WIDTH; x++) {
                final Sample sample = columns.get(z * FarViewColumns.WIDTH + x);
                final List<DhApiTerrainDataPoint> points = built.computeIfAbsent(sample, s -> wrappers.points(s, biomes, height));
                // SURFACE is what the three argument form, deprecated in DH 3.3.2, passes on
                dataSource.setApiDataPointColumn(x, z, EDhApiWorldGenerationStep.SURFACE, points);
            }
        }
        return dataSource;
    }

    /** The wrappers of DH's wrapper factory, each made once. */
    private static final class Wrappers {
        private final IDhApiLevelWrapper level;
        private final IDhApiWrapperFactory factory = DhApi.Delayed.wrapperFactory;
        private final IDhApiBlockStateWrapper air = factory.getAirBlockStateWrapper();
        private final IDhApiBlockStateWrapper[] states = new IDhApiBlockStateWrapper[Block.BLOCK_STATE_REGISTRY.size()];
        private final Map<String, IDhApiBiomeWrapper> biomes = new HashMap<>();

        Wrappers(IDhApiLevelWrapper level) {
            this.level = level;
        }

        List<DhApiTerrainDataPoint> points(Sample sample, List<String> table, int height) {
            final IDhApiBiomeWrapper biome = biome(table.get(sample.biome()));
            final List<DhApiTerrainDataPoint> points = new ArrayList<>(3);
            for (FarViewColumns.Point point : FarViewColumns.points(sample, height,
                    id -> Block.BLOCK_STATE_REGISTRY.byId(id).getLightEmission())) {
                // Detail level 0: bottom and top are y indices in blocks; setApiDataPointColumn throws on any other
                points.add(DhApiTerrainDataPoint.create((byte) 0, point.blockLight(), point.skyLight(), point.bottom(), point.top(),
                        point.air() ? air : state(point.state()), biome));
            }
            return points;
        }

        private IDhApiBlockStateWrapper state(int id) {
            IDhApiBlockStateWrapper wrapper = states[id];
            if (wrapper == null) {
                // Step 1 held the pack's block_states to the registry and the pack reader to block_states: every id is one
                wrapper = factory.getBlockStateWrapper(new Object[]{Block.BLOCK_STATE_REGISTRY.byId(id)}, level);
                states[id] = wrapper;
            }
            return wrapper;
        }

        private IDhApiBiomeWrapper biome(String name) {
            return biomes.computeIfAbsent(name, n -> {
                try {
                    return factory.getBiomeWrapper(n, level);
                } catch (IOException e) {
                    throw new IllegalArgumentException("biome " + n + ": " + e.getMessage(), e);
                }
            });
        }
    }

    /**
     * DH's rows at data detail level b, the {@code DetailLevel} of its {@code FullData} table: DH's data
     * detail level is its section detail level less 6, so every data source handed over at section detail
     * level {@code 6 + b} is one row there ({@code API-M3-015} 写库条第 4 步).
     *
     * @param repo      DH's repository of the table
     * @param baseLevel b
     * @return the count, null when the connection gives no answer
     */
    private static @Nullable Long rowsAt(FullDataSourceV2Repo repo, int baseLevel) {
        return count(repo, "DetailLevel = " + baseLevel);
    }

    /**
     * {@code SELECT COUNT(*)} over DH's {@code FullData} table, on the calling thread's connection.
     *
     * @param repo  DH's repository of the table
     * @param where the condition
     * @return the count, null when the connection gives no answer (closed, or the query failed)
     */
    static @Nullable Long count(FullDataSourceV2Repo repo, String where) {
        try {
            final Map<String, Object> row = repo.queryDictionaryFirst("SELECT COUNT(*) AS n FROM FullData WHERE " + where);
            if (row == null || !(row.get("n") instanceof Number n)) return null;
            return n.longValue();
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static long databaseBytes(Path library) {
        long bytes = 0;
        for (String file : DATABASE) {
            try {
                bytes += Files.size(library.resolve(file));
            } catch (IOException _) {
                // a file SQLite has not made (-shm or -wal right after a checkpoint) counts as 0
            }
        }
        return bytes;
    }
}
