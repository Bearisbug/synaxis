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
import java.util.Arrays;
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
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bake of {@code docs/DESIGN-M3.md} {@code API-M3-015} (写库条, §11.3 {@code baking}): on a platform
 * thread of its own, the packs of an announcement become data sources at section detail level
 * {@code 6 + b}, handed to the running DH level with {@code applyToParent} set; then the rows are
 * counted on DH's own connection, the parent propagation waited for, and only then the completion
 * marker written. A library whose marker matched is only counted ({@code 载入核对}).
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
     * @param loadCheck  whether only the rows of a ready library are to be checked
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
                final FarViewCache.Marker marker = connection.marker();
                final Long rows = count(repo, "DetailLevel = " + announcement.baseLevel());
                if (marker != null && rows != null && rows == marker.sources()) {
                    cache.touch(key, System.currentTimeMillis());
                    connection.state(FarViewConnection.State.READY);
                    LOGGER.info("{}view {} ready ({} tiles)", FarViewClient.PREFIX, key, announcement.tiles().size());
                    return;
                }
                cache.deleteMarker(key);
                LOGGER.warn("{}view {} library does not match its marker: {} of {}", FarViewClient.PREFIX, key,
                        rows == null ? "no answer" : rows, marker == null ? "?" : marker.sources());
                final List<FarViewAnnouncement.Tile> missing = cache.missing(announcement.tiles());
                if (!missing.isEmpty() && !connection.download(missing)) return;
            }
            connection.state(FarViewConnection.State.BAKING);
            bake(dhLevel, repo, started);
        } catch (Exception | LinkageError e) {
            if (stopped()) return;
            connection.state(FarViewConnection.State.ABSENT);
            connection.failure("bake failed");
            LOGGER.warn("{}view {} bake failed", FarViewClient.PREFIX, key, e);
        } finally {
            connection.settle();
        }
    }

    /** One pack as read off the cache, with the tile it was announced as. */
    private record Pack(FarViewAnnouncement.Tile tile, FarViewPack.Reader reader) {
    }

    /** A data source handed to DH and the future of its write. */
    private record InFlight(CompletableFuture<Void> future, FullDataSourceV2 source) {
    }

    private void bake(IDhLevel dhLevel, FullDataSourceV2Repo repo, long started) throws IOException, FarViewPack.Invalid,
            InterruptedException {
        final FarViewAnnouncement announcement = connection.announcement();
        final FarViewCache cache = connection.cache();
        final String key = connection.viewKey();
        final int baseLevel = announcement.baseLevel();
        // The world height: DH 3.3.2 deprecates getHeight() for getMaxHeight(), which on a client level is ClientLevel#getHeight
        final int height = level.getMaxHeight();

        // 1. Every pack against the level, before anything is written
        final List<Pack> packs = new ArrayList<>();
        for (FarViewAnnouncement.Tile tile : announcement.tiles()) {
            final String hex = FarViewConnection.hex(tile);
            final FarViewPack.Reader reader = FarViewPack.Reader.read(Files.readAllBytes(cache.pack(hex)));
            final FarViewPack.Header header = reader.header();
            if (header.minY() != level.getMinHeight() || header.height() != height
                    || header.blockStates() != Block.BLOCK_STATE_REGISTRY.size()) {
                LOGGER.warn("{}Far view off: pack {} does not match the level (min_y {} height {} block_states {}; the level {}, {}, {})",
                        FarViewClient.PREFIX, hex.substring(0, 12), header.minY(), header.height(), header.blockStates(),
                        level.getMinHeight(), height, Block.BLOCK_STATE_REGISTRY.size());
                connection.state(FarViewConnection.State.ABSENT);
                connection.failure("pack does not match the level");
                return;
            }
            packs.add(new Pack(tile, reader));
        }

        // 2. The whole tiles' fine layers first, then the coarse tiles, in announcement order
        final boolean expand = DhTestHook.expandCoarse;
        final int perCoarseBlock = expand ? FarViewColumns.expansion(baseLevel) * FarViewColumns.expansion(baseLevel) : 1;
        long fineBlocks = 0;
        long coarseBlocks = 0;
        for (Pack pack : packs) {
            if (whole(pack)) fineBlocks += pack.reader().blocks(FarViewPack.Layer.FINE).size();
            else coarseBlocks += pack.reader().blocks(FarViewPack.Layer.COARSE).size();
        }
        final long sources = fineBlocks + coarseBlocks * perCoarseBlock;
        final long rowsAtBase = fineBlocks + (expand ? coarseBlocks * perCoarseBlock : 0);
        connection.progress(0, sources);

        final Wrappers wrappers = new Wrappers(level);
        final Deque<InFlight> inFlight = new ArrayDeque<>();
        long handed = 0;
        for (boolean wholeTiles : new boolean[]{true, false}) {
            for (Pack pack : packs) {
                if (whole(pack) != wholeTiles) continue;
                final FarViewPack.Layer layer = wholeTiles ? FarViewPack.Layer.FINE : FarViewPack.Layer.COARSE;
                final FarViewAnnouncement.Tile tile = pack.tile();
                for (FarViewPack.Block block : pack.reader().blocks(layer)) {
                    final List<Sample> samples = pack.reader().samples(block);
                    final List<FarViewColumns.Source> made = wholeTiles
                            ? List.of(FarViewColumns.fine(tile.tileX(), tile.tileZ(), baseLevel, block.bx(), block.bz(), samples))
                            : expand ? FarViewColumns.coarse(tile.tileX(), tile.tileZ(), baseLevel, block.bx(), block.bz(), samples)
                            : List.of(FarViewColumns.coarseUnexpanded(tile.tileX(), tile.tileZ(), block.bx(), block.bz(), samples));
                    for (FarViewColumns.Source source : made) {
                        // 3. Handed over one by one, checking the cancel flag before each
                        if (stopped()) return;
                        final FullDataSourceV2 dataSource = build(source, pack.reader().biomes(), height, wrappers);
                        if (handed == sources - 1 && DhTestHook.holdBeforeLast != null) {
                            while (!inFlight.isEmpty()) {
                                if (!completeOldest(inFlight)) return;
                            }
                            final CountDownLatch reached = DhTestHook.reachedLast;
                            if (reached != null) reached.countDown();
                            await(DhTestHook.holdBeforeLast);
                        }
                        inFlight.addLast(new InFlight(dhLevel.updateDataSourcesAsync(dataSource), dataSource));
                        connection.progress(++handed, sources);
                        while (inFlight.size() >= IN_FLIGHT) {
                            if (!completeOldest(inFlight)) return;
                        }
                    }
                }
            }
        }
        while (!inFlight.isEmpty()) {
            if (!completeOldest(inFlight)) return;
        }

        // 4. A completed future says nothing about the library: count its rows, then wait for the parents
        if (DhTestHook.writeCheck) {
            final Long rows = count(repo, "DetailLevel = " + baseLevel);
            if (rows == null || rows != rowsAtBase) {
                LOGGER.warn("{}view {} incomplete: {} of {} sources in the library", FarViewClient.PREFIX, key,
                        rows == null ? "no answer, so none" : rows, rowsAtBase);
                connection.state(FarViewConnection.State.ABSENT);
                connection.failure("incomplete");
                return;
            }
        }
        final long writeMillis = FarViewConnection.millisSince(started);
        if (DhTestHook.writeCheck) {
            final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PARENTS_MILLIS);
            Long pending = count(repo, "ApplyToParent = 1");
            while (pending != null && pending > 0 && System.nanoTime() < deadline) {
                if (stopped()) return;
                Thread.sleep(POLL_MILLIS);
                pending = count(repo, "ApplyToParent = 1");
            }
            if (pending == null || pending > 0) {
                LOGGER.warn("{}view {} parents pending: {}", FarViewClient.PREFIX, key, pending == null ? "no answer" : pending);
                connection.state(FarViewConnection.State.ABSENT);
                connection.failure("parents pending");
                return;
            }
        }
        if (stopped()) return;

        // 5. Complete: the marker, the bake line, the cache held to its limit
        final long millis = FarViewConnection.millisSince(started);
        final long visible = FarViewConnection.millisSince(connection.announcedNanos());
        final long now = System.currentTimeMillis();
        cache.writeMarker(new FarViewCache.Marker(FarViewCache.MARKER_FORMAT, key, connection.dhVersion(),
                FarViewClient.BAKE_CODE_VERSION, announcement.tiles().stream().map(FarViewCache.MarkerTile::of).toList(),
                sources, millis, now, now));
        final long databaseBytes = databaseBytes(cache.library(key));
        final long levelLoaded = connection.levelLoadedNanos();
        connection.timings(new FarViewConnection.Timings(millis, writeMillis, visible, databaseBytes, sources,
                levelLoaded == 0 ? -1 : FarViewConnection.millisSince(levelLoaded)));
        connection.state(FarViewConnection.State.READY);
        LOGGER.info("{}view {} baked in {} ms: {} sources, {} B; written {} ms, visible {} ms after the announcement",
                FarViewClient.PREFIX, key, millis, sources, databaseBytes, writeMillis, visible);
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

    private static boolean whole(Pack pack) {
        return pack.tile().layer() == FarViewAnnouncement.LAYER_FULL;
    }

    private boolean stopped() {
        return connection.cancelled().get() && !DhTestHook.ignoreCancel;
    }

    /** Waits for the oldest write, waking every {@value #POLL_MILLIS} ms for the cancel flag, then closes its source. */
    private boolean completeOldest(Deque<InFlight> inFlight) throws InterruptedException {
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
        private final int[] emission = new int[Block.BLOCK_STATE_REGISTRY.size()];
        private final Map<String, IDhApiBiomeWrapper> biomes = new HashMap<>();

        Wrappers(IDhApiLevelWrapper level) {
            this.level = level;
            Arrays.fill(emission, -1);
        }

        List<DhApiTerrainDataPoint> points(Sample sample, List<String> table, int height) {
            final IDhApiBiomeWrapper biome = biome(table.get(sample.biome()));
            final List<DhApiTerrainDataPoint> points = new ArrayList<>(3);
            for (FarViewColumns.Point point : FarViewColumns.points(sample, height, this::emission)) {
                points.add(DhApiTerrainDataPoint.create((byte) 0, point.blockLight(), point.skyLight(), point.bottom(), point.top(),
                        point.air() ? air : state(point.state()), biome));
            }
            return points;
        }

        private IDhApiBlockStateWrapper state(int id) {
            IDhApiBlockStateWrapper wrapper = states[id];
            if (wrapper == null) {
                wrapper = factory.getBlockStateWrapper(new Object[]{blockState(id)}, level);
                states[id] = wrapper;
            }
            return wrapper;
        }

        private int emission(int id) {
            if (emission[id] < 0) emission[id] = blockState(id).getLightEmission();
            return emission[id];
        }

        private static BlockState blockState(int id) {
            final BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
            if (state == null) throw new IllegalArgumentException("block state " + id + " is not one of this client's");
            return state;
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
