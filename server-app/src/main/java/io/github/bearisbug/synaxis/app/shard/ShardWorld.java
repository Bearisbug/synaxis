package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.ShutdownSequence;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.WorldBorder;
import io.github.bearisbug.synaxis.instance.light.LightEngine;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.synx.OverlayOptions;
import io.github.bearisbug.synaxis.synx.OverlayStats;
import io.github.bearisbug.synaxis.synx.OverlayStore;
import io.github.bearisbug.synaxis.synx.SynxBuildVerifier;
import io.github.bearisbug.synaxis.synx.SynxChunkLoader;
import io.github.bearisbug.synaxis.synx.SynxError;
import io.github.bearisbug.synaxis.synx.SynxException;
import io.github.bearisbug.synaxis.synx.SynxExpectations;
import io.github.bearisbug.synaxis.synx.SynxFormat;
import io.github.bearisbug.synaxis.synx.SynxHandlers;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.synx.SynxOptions;
import io.github.bearisbug.synaxis.synx.SynxRegion;
import io.github.bearisbug.synaxis.synx.SynxSettings;
import io.github.bearisbug.synaxis.synx.SynxSlot;
import io.github.bearisbug.synaxis.synx.SynxStreamStats;
import io.github.bearisbug.synaxis.timer.ExecutionType;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import io.github.bearisbug.synaxis.world.DimensionType;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The map mounting mode of the shard host ({@code docs/DESIGN-M3.md} {@code API-M3-006}): checks the
 * map release, builds the map instance on {@link SynxChunkLoader} with the overlay store behind it,
 * and reports the streaming counters. {@code Main} drives it at two steps of its startup, and keeps
 * the admission, the spawn listener and the event ledger, which are the host's own.
 * <p>
 * Every refusal is an {@link IllegalArgumentException}, whose message a {@link SynxException} leads
 * with its synx code; {@code Main} turns it into {@code Refusing to start: …} and exit code 1. All of
 * them come before the first write to the disk, which is {@link OverlayStore#open}.
 */
public final class ShardWorld {
    /** The dimension type of the map instance, one whole string for the translation key gate. */
    public static final String DIMENSION_TYPE = "synaxis:map";
    /** The largest chunk view distance a map shard serves: {@code view_bounds} reach 17 chunks past the bounds. */
    static final int MAX_VIEW_DISTANCE = 16;
    /** Where the overlay store lives, under the working directory. */
    static final Path OVERLAY_ROOT = Path.of("overlay");
    /** How often {@code EVT:chunk_stream_stats} is written. */
    static final Duration STATS_INTERVAL = Duration.ofSeconds(60);
    /** §9: the column a player spawns on, the center of the center chunk of the bounds. */
    private static final int SPAWN_COLUMN = 8 * 16 + 8;

    private static final Logger LOGGER = LoggerFactory.getLogger(ShardWorld.class);

    private final ServerProcess process;
    private final ShardSettings settings;
    private final SynxOptions synxOptions;
    private final OverlayOptions overlayOptions;
    private final SynxManifest manifest;
    private final RegistryKey<DimensionType> dimensionType;
    private final long verifyMillis;
    // Set by #start, on the startup thread, before the process ticks
    private @Nullable SynxChunkLoader loader;
    private @Nullable OverlayStore overlay;
    // Read and replaced by the statistics task only, on the tick scheduler thread
    private @Nullable SynxStreamStats lastStream;
    private @Nullable OverlayStats lastOverlay;

    /**
     * The map instance and where a player spawns in it.
     *
     * @param instance the map instance
     * @param spawn    the spawn point
     */
    public record Started(InstanceContainer instance, Pos spawn) {
    }

    private ShardWorld(ServerProcess process, ShardSettings settings, SynxOptions synxOptions,
                       OverlayOptions overlayOptions, SynxManifest manifest, RegistryKey<DimensionType> dimensionType,
                       long verifyMillis) {
        this.process = process;
        this.settings = settings;
        this.synxOptions = synxOptions;
        this.overlayOptions = overlayOptions;
        this.manifest = manifest;
        this.dimensionType = dimensionType;
        this.verifyMillis = verifyMillis;
    }

    /**
     * Step (3) of {@code Main}, after the match dimension type, while the registries still take new
     * entries: checks the view and simulation distances, runs the start-up check of
     * {@code API-M3-002} on the release, registers the map dimension type from the manifest's world,
     * and the block handlers the baker registered. Writes nothing.
     *
     * @param process        the engine process, whose configuration, registries and blocks count
     * @param settings       the host keys, with a map mounted
     * @param synxOptions    the {@code :synx} keys
     * @param overlayOptions the overlay keys
     * @return the prepared world
     * @throws IllegalArgumentException for a refused start
     */
    public static ShardWorld prepare(ServerProcess process, ShardSettings settings, SynxOptions synxOptions,
                                     OverlayOptions overlayOptions) {
        final int viewDistance = process.config().chunkViewDistance();
        if (viewDistance > MAX_VIEW_DISTANCE) {
            throw new IllegalArgumentException("synaxis.chunk-view-distance=" + viewDistance + " is above "
                    + MAX_VIEW_DISTANCE + ", the most a map shard serves (view_bounds reach "
                    + SynxChunkLoader.VIEW_MARGIN + " chunks past its bounds)");
        }
        final int simulationDistance = settings.simulationDistance();
        if (simulationDistance < 1 || simulationDistance > viewDistance) {
            throw new IllegalArgumentException(ShardSettings.SIMULATION_DISTANCE + "=" + simulationDistance
                    + " is not from 1 to the chunk view distance " + viewDistance
                    + ": the simulation distance cannot exceed the view distance");
        }
        final Path buildDir = settings.buildDir();
        final long started = System.nanoTime();
        final SynxManifest manifest;
        try {
            manifest = verify(process, buildDir);
        } catch (SynxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException(SynxError.MANIFEST.code() + ": " + buildDir + ": " + e.getMessage(), e);
        }
        final long verifyMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        final SynxManifest.World world = manifest.world();
        final RegistryKey<DimensionType> dimensionType = process.dimensionType().register(Key.key(DIMENSION_TYPE),
                DimensionType.builder().minY(world.minY()).height(world.height()).logicalHeight(world.height()).build());
        SynxHandlers.registerAll(process.block());
        LOGGER.info("Map release {} verified in {} ms: bounds={} world={}", manifest.buildId(), verifyMillis,
                manifest.bounds(), world);
        return new ShardWorld(process, settings, synxOptions, overlayOptions, manifest, dimensionType, verifyMillis);
    }

    /**
     * The start-up check, on the expectations of this process: its registries and compression
     * threshold, the light engine version, and the world the manifest declares, which the map
     * dimension type is then built on. The scope is {@code view_bounds}.
     */
    private static SynxManifest verify(ServerProcess process, Path buildDir) throws IOException, SynxException {
        final Path manifestFile = buildDir.resolve(SynxManifest.FILE_NAME);
        if (!Files.isRegularFile(manifestFile)) {
            throw new SynxException(SynxError.MANIFEST, SynxManifest.FILE_NAME, "missing in " + buildDir);
        }
        final SynxManifest declared = SynxManifest.fromJson(Files.readString(manifestFile));
        final SynxManifest.World world = declared.world();
        final SynxExpectations expectations = SynxExpectations.of(process.registries(), process.compressionThreshold(),
                world.minY(), world.height(), LightEngine.VERSION);
        return SynxBuildVerifier.verify(buildDir, expectations, viewBounds(declared.bounds()));
    }

    static SynxManifest.Bounds viewBounds(SynxManifest.Bounds bounds) {
        final int margin = SynxChunkLoader.VIEW_MARGIN;
        return new SynxManifest.Bounds(bounds.minX() - margin, bounds.minZ() - margin,
                bounds.maxX() + margin, bounds.maxZ() + margin);
    }

    /**
     * Step (5) of {@code Main}: opens the overlay store (the first write to the disk, after every other
     * refusal) and registers its final flush after the players' save, builds the map instance with its
     * simulation distance, unload grace period and world border, and schedules the write phase of the
     * overlay store at the end of every tick and {@code EVT:chunk_stream_stats} every 60 s.
     *
     * @param shutdown the shutdown sequence, still taking steps
     * @param events   where the events go, the event ledger of the host
     * @return the map instance and the spawn point
     * @throws IllegalArgumentException for a refused start
     */
    public Started start(ShutdownSequence shutdown, BiConsumer<String, Map<String, ?>> events) {
        return start(shutdown, events, OVERLAY_ROOT);
    }

    /** {@link #start(ShutdownSequence, BiConsumer)} with the overlay store elsewhere than the working directory, for the tests. */
    Started start(ShutdownSequence shutdown, BiConsumer<String, Map<String, ?>> events, Path overlayRoot) {
        final SynxSettings overlaySettings = overlaySettings(manifest, process.compressionThreshold());
        final OverlayStore overlay;
        final SynxChunkLoader loader;
        try {
            overlay = OverlayStore.open(overlayRoot, manifest, overlaySettings, overlayOptions);
            shutdown.addAfterPlayers("overlay", overlay::finalFlush);
            loader = new SynxChunkLoader(settings.buildDir(), manifest, manifest.bounds(), synxOptions, overlay, overlay);
        } catch (SynxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException("the map release " + manifest.buildId() + " cannot be opened: "
                    + e.getMessage(), e);
        }
        this.overlay = overlay;
        this.loader = loader;

        final InstanceContainer instance = process.instance().createInstanceContainer(dimensionType, loader);
        instance.setSimulationDistance(settings.simulationDistance());
        instance.setChunkUnloadGraceTicks(settings.chunkUnloadGraceTicks());
        final SynxManifest.Bounds bounds = manifest.bounds();
        instance.setWorldBorder(worldBorder(bounds));
        if (bounds.maxX() - bounds.minX() != bounds.maxZ() - bounds.minZ()) {
            LOGGER.warn("The bounds {} of {} are not square: the world border covers the long side, a player can walk "
                    + "into the read only chunks past the short one", bounds, manifest.buildId());
        }
        final Pos spawn = spawn(settings.buildDir(), manifest);

        process.scheduler().scheduleTask(overlay::tickEnd, TaskSchedule.nextTick(), TaskSchedule.nextTick(),
                ExecutionType.TICK_END);
        this.lastStream = loader.stats();
        this.lastOverlay = overlay.stats();
        process.scheduler().buildTask(() -> recordStreamStats(events))
                .delay(STATS_INTERVAL).repeat(STATS_INTERVAL).schedule();
        LOGGER.info("Map mounted: build_id={} simulation_distance={} chunk_unload_grace_ticks={} spawn={} overlay={}",
                manifest.buildId(), settings.simulationDistance(), settings.chunkUnloadGraceTicks(), spawn,
                overlayRoot.resolve(manifest.buildId()));
        return new Started(instance, spawn);
    }

    /**
     * What an overlay record of the release is encoded with: the release's protocol, world, registry
     * digest and versions, the compression threshold of the process (which the start-up check held to
     * the release's), no segment ④.
     */
    static SynxSettings overlaySettings(SynxManifest manifest, int compressionThreshold) {
        return new SynxSettings(manifest.protocolVersion(), compressionThreshold, -1, manifest.world().minY(),
                manifest.world().height(), manifest.registryDigest(), manifest.lightEngineVersion(),
                manifest.bakerVersion(), false);
    }

    /**
     * What {@code EVT:shard_started} carries, which {@code Main} writes once the port is bound.
     *
     * @return the attributes
     * @throws IllegalStateException before {@link #start}
     */
    public Map<String, Object> startedAttributes() {
        final SynxChunkLoader loader = this.loader;
        final OverlayStore overlay = this.overlay;
        if (loader == null || overlay == null) throw new IllegalStateException("the map world has not started");
        final SynxStreamStats stream = loader.stats();
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("build_id", manifest.buildId());
        attributes.put("verify_ms", verifyMillis);
        attributes.put("regions", stream.regionsMapped());
        attributes.put("bytes_mapped", stream.bytesMapped());
        attributes.put("overlay_records", overlay.stats().records());
        // M3-E replays the world state flags
        attributes.put("flags_replayed", 0);
        attributes.put("flags_ms", 0);
        return attributes;
    }

    private void recordStreamStats(BiConsumer<String, Map<String, ?>> events) {
        final SynxChunkLoader loader = this.loader;
        final OverlayStore overlay = this.overlay;
        final SynxStreamStats lastStream = this.lastStream;
        final OverlayStats lastOverlay = this.lastOverlay;
        if (loader == null || overlay == null || lastStream == null || lastOverlay == null) return;
        final SynxStreamStats stream = loader.stats();
        final OverlayStats overlayStats = overlay.stats();
        this.lastStream = stream;
        this.lastOverlay = overlayStats;
        events.accept("chunk_stream_stats", streamStats(lastStream, stream, lastOverlay, overlayStats));
    }

    /**
     * {@code EVT:chunk_stream_stats} ({@code docs/DESIGN-M3.md} §20): the counters as the difference
     * of two readings, the current values and the window maxima as read.
     */
    static Map<String, Object> streamStats(SynxStreamStats before, SynxStreamStats now, OverlayStats overlayBefore,
                                           OverlayStats overlayNow) {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("handle_sent", now.handleSent() - before.handleSent());
        attributes.put("overlay_sent", now.overlaySent() - before.overlaySent());
        attributes.put("bytes_sent", now.bytesSent() - before.bytesSent());
        attributes.put("failed", now.failed() - before.failed());
        attributes.put("loaded", now.loaded() - before.loaded());
        attributes.put("overlay_resident", overlayNow.resident());
        attributes.put("overlay_evicted", overlayNow.evicted() - overlayBefore.evicted());
        attributes.put("evict_failed", overlayNow.evictFailed() - overlayBefore.evictFailed());
        attributes.put("flush_appended", overlayNow.flushAppended() - overlayBefore.flushAppended());
        attributes.put("flush_fsyncs", overlayNow.flushFsyncs() - overlayBefore.flushFsyncs());
        attributes.put("flush_lag_s_max", overlayNow.flushLagSMax());
        attributes.put("write_phase_ms_max", overlayNow.writePhaseMsMax());
        // M3-D computes the light at run time
        attributes.put("relight_queue_p99_ms", 0);
        attributes.put("relight_ms_p99", 0);
        attributes.put("unloaded", now.unloaded() - before.unloaded());
        attributes.put("section_cache_bytes", now.sectionCacheBytes());
        // M3-E replays the world state flags
        attributes.put("flag_conflicts", 0);
        return attributes;
    }

    /** Centered on the bounds, as wide as their long side. */
    static WorldBorder worldBorder(SynxManifest.Bounds bounds) {
        final double centerX = (bounds.minX() * 16.0 + (bounds.maxX() + 1) * 16.0) / 2;
        final double centerZ = (bounds.minZ() * 16.0 + (bounds.maxZ() + 1) * 16.0) / 2;
        final int side = Math.max(bounds.maxX() - bounds.minX() + 1, bounds.maxZ() - bounds.minZ() + 1);
        return WorldBorder.defaultBorder().withCenter(centerX, centerZ).withDiameter(side * 16.0);
    }

    /**
     * §28.1 the spawn point: the column at the center of the center chunk of the bounds, standing on
     * its {@code surface_y} of segment ②. A center chunk without a readable record puts the spawn at
     * the top of the world.
     */
    static Pos spawn(Path buildDir, SynxManifest manifest) {
        final SynxManifest.Bounds bounds = manifest.bounds();
        final int chunkX = Math.floorDiv(bounds.minX() + bounds.maxX(), 2);
        final int chunkZ = Math.floorDiv(bounds.minZ() + bounds.maxZ(), 2);
        final double x = chunkX * 16 + 8.5, z = chunkZ * 16 + 8.5;
        final Path file = buildDir.resolve("regions").resolve(SynxFormat.regionFileName(SynxFormat.region(chunkX),
                SynxFormat.region(chunkZ)));
        String missing = "no record";
        try (Arena arena = Arena.ofConfined()) {
            if (Files.isRegularFile(file) && SynxRegion.open(file, arena).slot(chunkX, chunkZ) instanceof SynxSlot.Present present) {
                final short surfaceY = present.navigation().get(
                        ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 32 + SPAWN_COLUMN * 2L);
                return new Pos(x, surfaceY, z);
            }
        } catch (IOException | SynxException e) {
            missing = e.getMessage();
        }
        final int top = manifest.world().minY() + manifest.world().height();
        LOGGER.warn("The center chunk {},{} of {} has no readable record ({}), players spawn at the top of the world, y {}",
                chunkX, chunkZ, manifest.buildId(), missing, top);
        return new Pos(x, top, z);
    }
}
