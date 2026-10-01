package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.ShutdownSequence;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack;
import io.github.bearisbug.synaxis.game.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.WorldBorder;
import io.github.bearisbug.synaxis.instance.light.LightEngine;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.synx.OverlayOptions;
import io.github.bearisbug.synaxis.synx.OverlayStats;
import io.github.bearisbug.synaxis.synx.OverlayStore;
import io.github.bearisbug.synaxis.synx.RelightStats;
import io.github.bearisbug.synaxis.synx.SynxBuildVerifier;
import io.github.bearisbug.synaxis.synx.SynxChunkLoader;
import io.github.bearisbug.synaxis.synx.SynxDigests;
import io.github.bearisbug.synaxis.synx.SynxError;
import io.github.bearisbug.synaxis.synx.SynxException;
import io.github.bearisbug.synaxis.synx.SynxExpectations;
import io.github.bearisbug.synaxis.synx.SynxFormat;
import io.github.bearisbug.synaxis.synx.SynxHandlers;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.synx.SynxOptions;
import io.github.bearisbug.synaxis.synx.SynxRegion;
import io.github.bearisbug.synaxis.synx.SynxRelight;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
    /** {@code API-M3-015}: the coarse prefixes of the tiles up to 256 chunks, 4,096 blocks, past the bounds are announced. */
    static final int COARSE_REACH = 256;

    private static final Logger LOGGER = LoggerFactory.getLogger(ShardWorld.class);

    private final ServerProcess process;
    private final ShardSettings settings;
    private final SynxOptions synxOptions;
    private final OverlayOptions overlayOptions;
    private final SynxManifest manifest;
    private final RegistryKey<DimensionType> dimensionType;
    private final long verifyMillis;
    private final @Nullable ShardDirectory directory;
    // Set by #start, on the startup thread, before the process ticks
    private @Nullable SynxChunkLoader loader;
    private @Nullable OverlayStore overlay;
    private @Nullable WorldFlagsImpl flags;
    private int farViewTiles;
    // Read and replaced by the statistics task only, on the tick scheduler thread
    private @Nullable SynxStreamStats lastStream;
    private @Nullable OverlayStats lastOverlay;
    private @Nullable RelightStats lastRelight;
    private @Nullable SynxRelight relight;
    private long lastFlagConflicts;

    /**
     * The map instance and where a player spawns in it.
     *
     * @param instance the map instance
     * @param spawn    the spawn point
     * @param relight  the runtime light of the instance, whose counters the light fixture and the tests read
     * @param farView  the encoded far view announcement ({@code API-M3-015}), null when the shard announces none
     */
    public record Started(InstanceContainer instance, Pos spawn, SynxRelight relight, byte @Nullable [] farView) {
    }

    /**
     * A tile of {@code farview[]} the shard announces.
     *
     * @param pack  the manifest entry of the tile
     * @param layer {@link FarViewAnnouncement#LAYER_FULL} or {@link FarViewAnnouncement#LAYER_COARSE}
     */
    record AnnouncedTile(SynxManifest.FarView pack, int layer) {
    }

    /**
     * {@code API-M3-015} 选瓦片: the tiles of {@code farview[]} meeting the bounds are announced whole, those
     * meeting the bounds grown by {@value #COARSE_REACH} chunks (4,096 blocks) on every side but not the
     * bounds by their coarse prefix, the rest not at all.
     *
     * @param farview the entries of the manifest
     * @param bounds  the bounds the shard serves, B
     * @return the tiles to announce, by x and then z
     */
    static List<AnnouncedTile> announcedTiles(List<SynxManifest.FarView> farview, SynxManifest.Bounds bounds) {
        final SynxManifest.Bounds reach = new SynxManifest.Bounds(bounds.minX() - COARSE_REACH, bounds.minZ() - COARSE_REACH,
                bounds.maxX() + COARSE_REACH, bounds.maxZ() + COARSE_REACH);
        final List<AnnouncedTile> tiles = new ArrayList<>();
        for (SynxManifest.FarView pack : farview) {
            if (meets(pack, bounds)) tiles.add(new AnnouncedTile(pack, FarViewAnnouncement.LAYER_FULL));
            else if (meets(pack, reach)) tiles.add(new AnnouncedTile(pack, FarViewAnnouncement.LAYER_COARSE));
        }
        tiles.sort(Comparator.comparingInt((AnnouncedTile tile) -> tile.pack().tileX())
                .thenComparingInt(tile -> tile.pack().tileZ()));
        return List.copyOf(tiles);
    }

    /** Whether the 256 × 256 chunks of the tile of {@code pack} meet {@code bounds}. */
    private static boolean meets(SynxManifest.FarView pack, SynxManifest.Bounds bounds) {
        final int minX = pack.tileX() << FarViewPack.TILE_SHIFT, minZ = pack.tileZ() << FarViewPack.TILE_SHIFT;
        final int side = 1 << FarViewPack.TILE_SHIFT;
        return minX <= bounds.maxX() && minX + side - 1 >= bounds.minX() && minZ <= bounds.maxZ()
                && minZ + side - 1 >= bounds.minZ();
    }

    /**
     * {@code API-M3-015} 远景宣告, computed once when the shard starts: the tiles of {@link #announcedTiles},
     * each pack held to the manifest first, its size and SHA-256, and for a tile announced by its coarse
     * prefix also {@code coarse_bytes} against the {@code fine_offset} of its header and
     * {@code coarse_sha256} against its first {@code coarse_bytes} bytes. Nothing is announced without a
     * url root, without a tile meeting the bounds, when one pack does not match, or when the tiles make
     * no announcement (over 64 of them, a url over 1,024 bytes); one line of the log says which, a WARN
     * for the last two. None of it refuses the start: the far view is optional.
     *
     * @return the announcement, null when there is none
     */
    static @Nullable FarViewAnnouncement farViewAnnouncement(Path buildDir, SynxManifest manifest,
                                                            SynxManifest.Bounds bounds, String urlRoot) {
        if (urlRoot.isEmpty()) {
            LOGGER.info("Far view off: {} is empty", ShardSettings.FARVIEW_URL_ROOT);
            return null;
        }
        final List<AnnouncedTile> tiles = announcedTiles(manifest.farview(), bounds);
        if (tiles.stream().noneMatch(tile -> tile.layer() == FarViewAnnouncement.LAYER_FULL)) {
            LOGGER.info("Far view off: no far view tile of {} meets the bounds {}", manifest.buildId(), bounds);
            return null;
        }
        final SynxManifest.FarView first = tiles.getFirst().pack();
        for (AnnouncedTile tile : tiles) {
            final SynxManifest.FarView pack = tile.pack();
            final String file = "farview/t." + pack.tileX() + "." + pack.tileZ() + ".hfp";
            final String mismatch = pack.baseLevel() != first.baseLevel() || pack.coarseLevel() != first.coarseLevel()
                    ? "has the levels " + pack.baseLevel() + "/" + pack.coarseLevel() + " in the manifest, another tile "
                    + first.baseLevel() + "/" + first.coarseLevel()
                    : mismatch(buildDir.resolve(file), pack, tile.layer());
            if (!mismatch.isEmpty()) {
                LOGGER.warn("Far view off: {} of {} {}", file, manifest.buildId(), mismatch);
                return null;
            }
        }
        final FarViewAnnouncement announcement;
        try {
            final List<FarViewAnnouncement.Tile> announced = new ArrayList<>(tiles.size());
            for (AnnouncedTile tile : tiles) {
                final SynxManifest.FarView pack = tile.pack();
                final boolean whole = tile.layer() == FarViewAnnouncement.LAYER_FULL;
                final byte[] digest = whole ? pack.sha256() : pack.coarseSha256();
                announced.add(new FarViewAnnouncement.Tile(pack.tileX(), pack.tileZ(), tile.layer(),
                        whole ? pack.bytes() : pack.coarseBytes(), digest,
                        urlRoot + "/" + manifest.mapId() + "/farview/" + SynxDigests.hex(digest) + ".hfp"));
            }
            announcement = new FarViewAnnouncement(manifest.buildId(), first.baseLevel(), first.coarseLevel(),
                    manifest.world().minY(), manifest.world().height(), announced);
        } catch (MalformedMessageException e) {
            LOGGER.warn("Far view off: the far view tiles of {} make no announcement: {}", manifest.buildId(), e.getMessage());
            return null;
        }
        final long coarse = tiles.stream().filter(tile -> tile.layer() == FarViewAnnouncement.LAYER_COARSE).count();
        LOGGER.info("Far view: {} tile(s), {} coarse, from {}", tiles.size() - coarse, coarse, urlRoot);
        return announcement;
    }

    /** How the pack in {@code file} differs from its manifest entry, empty when it does not. */
    private static String mismatch(Path file, SynxManifest.FarView pack, int layer) {
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            return "cannot be read: " + e;
        }
        if (bytes.length != pack.bytes()) return "is " + bytes.length + " bytes, the manifest says " + pack.bytes();
        if (!Arrays.equals(SynxDigests.sha256(bytes), pack.sha256())) return "does not match the sha256 of the manifest";
        if (layer == FarViewAnnouncement.LAYER_FULL) return "";
        final long fineOffset;
        try {
            fineOffset = FarViewPack.Reader.read(bytes).header().fineOffset();
        } catch (FarViewPack.Invalid e) {
            return "is not a far view pack: " + e.getMessage();
        }
        if (fineOffset != pack.coarseBytes()) {
            return "has its coarse prefix in " + fineOffset + " bytes, the manifest says coarse_bytes " + pack.coarseBytes();
        }
        if (!Arrays.equals(SynxDigests.sha256(Arrays.copyOf(bytes, (int) fineOffset)), pack.coarseSha256())) {
            return "has a coarse prefix that does not match the coarse_sha256 of the manifest";
        }
        return "";
    }

    private ShardWorld(ServerProcess process, ShardSettings settings, SynxOptions synxOptions,
                       OverlayOptions overlayOptions, SynxManifest manifest, RegistryKey<DimensionType> dimensionType,
                       long verifyMillis, @Nullable ShardDirectory directory) {
        this.process = process;
        this.settings = settings;
        this.synxOptions = synxOptions;
        this.overlayOptions = overlayOptions;
        this.manifest = manifest;
        this.dimensionType = dimensionType;
        this.verifyMillis = verifyMillis;
        this.directory = directory;
    }

    /**
     * Step (3) of {@code Main}, after the match dimension type, while the registries still take new
     * entries: checks the view and simulation distances, runs the start-up check of
     * {@code API-M3-002} on the release, registers the map dimension type from the manifest's world,
     * and the block handlers the baker registered. In the directory mode it also checks the shard
     * directory against the release and the spawn column of the region against its gates, and the
     * start-up check covers the view of the region instead of the whole release. Writes nothing.
     *
     * @param process        the engine process, whose configuration, registries and blocks count
     * @param settings       the host keys, with a map mounted
     * @param synxOptions    the {@code :synx} keys
     * @param overlayOptions the overlay keys
     * @param directory      the shard directory in the directory mode ({@code API-M3-010}), whose
     *                       region of this shard is the bounds; null outside it: the bounds are then
     *                       the manifest's
     * @return the prepared world
     * @throws IllegalArgumentException for a refused start
     */
    public static ShardWorld prepare(ServerProcess process, ShardSettings settings, SynxOptions synxOptions,
                                     OverlayOptions overlayOptions, @Nullable ShardDirectory directory) {
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
            manifest = verify(process, settings, directory);
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
        return new ShardWorld(process, settings, synxOptions, overlayOptions, manifest, dimensionType, verifyMillis,
                directory);
    }

    /**
     * The start-up check, on the expectations of this process: its registries and compression
     * threshold, the light engine version, and the world the manifest declares, which the map
     * dimension type is then built on. The scope is the {@code view_bounds} of the bounds the shard
     * serves, its region in the directory mode. A shard directory is checked against the declared
     * manifest first.
     */
    private static SynxManifest verify(ServerProcess process, ShardSettings settings, @Nullable ShardDirectory directory)
            throws IOException, SynxException {
        final Path buildDir = settings.buildDir();
        final Path manifestFile = buildDir.resolve(SynxManifest.FILE_NAME);
        if (!Files.isRegularFile(manifestFile)) {
            throw new SynxException(SynxError.MANIFEST, SynxManifest.FILE_NAME, "missing in " + buildDir);
        }
        final SynxManifest declared = SynxManifest.fromJson(Files.readString(manifestFile));
        if (directory != null) checkDirectory(directory, declared, settings);
        final SynxManifest.World world = declared.world();
        final SynxExpectations expectations = SynxExpectations.of(process.registries(), process.compressionThreshold(),
                world.minY(), world.height(), LightEngine.VERSION);
        return SynxBuildVerifier.verify(buildDir, expectations, viewBounds(bounds(declared, directory)));
    }

    /**
     * The shard directory against the manifest it serves ({@code API-M3-010}), then the spawn column
     * against the gates of the region. The directory does not know its file, so the prefix every
     * refusal about it starts with goes in front of the detail {@link ShardDirectory#checkAgainst} gives.
     */
    private static void checkDirectory(ShardDirectory directory, SynxManifest declared, ShardSettings settings) {
        try {
            directory.checkAgainst(declared);
        } catch (IllegalArgumentException e) {
            final Path file = Objects.requireNonNull(settings.mapRoot(), "no map is mounted")
                    .resolve(ShardDirectory.FILE_NAME).toAbsolutePath();
            throw new IllegalArgumentException("the shard directory " + file + " is not valid: " + e.getMessage(), e);
        }
        checkSpawnColumn(directory);
    }

    /**
     * {@code API-M3-006} step (3): a player who joins spawns on the spawn column of the region, which
     * its bounds alone decide; inside a gate the first move would send the player away. Judged by the
     * column, x and z, as the y of the spawn point is only known once segment ② is read in step (5).
     *
     * @param directory the shard directory, whose region of this shard counts
     * @throws IllegalArgumentException if the column is inside a gate of the region
     */
    static void checkSpawnColumn(ShardDirectory directory) {
        final ShardDirectory.Region region = directory.ownRegion();
        final SynxManifest.Bounds bounds = region.bounds();
        final int x = centerChunk(bounds.minX(), bounds.maxX()) * 16 + 8;
        final int z = centerChunk(bounds.minZ(), bounds.maxZ()) * 16 + 8;
        for (ShardDirectory.Gate gate : region.gates()) {
            final ShardDirectory.Frame frame = gate.frame();
            if (x >= frame.minX() && x <= frame.maxX() && z >= frame.minZ() && z <= frame.maxZ()) {
                throw new IllegalArgumentException("the spawn column " + x + ", " + z + " of " + region.regionId()
                        + " is inside the gate " + gate.gateId() + ": a player would be sent away on joining");
            }
        }
    }

    /**
     * The bounds the shard serves ({@code API-M3-005} {@code bounds}): the region of the shard in the
     * directory mode ({@code API-M3-010}), the release's otherwise.
     */
    private static SynxManifest.Bounds bounds(SynxManifest manifest, @Nullable ShardDirectory directory) {
        return directory == null ? manifest.bounds() : directory.ownRegion().bounds();
    }

    /** §28.1: the center chunk of the bounds on one axis, whose center column players spawn on. */
    private static int centerChunk(int min, int max) {
        return Math.floorDiv(min + max, 2);
    }

    static SynxManifest.Bounds viewBounds(SynxManifest.Bounds bounds) {
        final int margin = SynxChunkLoader.VIEW_MARGIN;
        return new SynxManifest.Bounds(bounds.minX() - margin, bounds.minZ() - margin,
                bounds.maxX() + margin, bounds.maxZ() + margin);
    }

    /**
     * Step (5) of {@code Main}: reads the world state flags (a refusal still, before the first write),
     * opens the overlay store (the first write to the disk, after every other refusal) and registers
     * its final flush after the players' save, builds the map instance with its simulation distance
     * and unload grace period on the bounds the shard serves (its region in the directory mode, the
     * chunks past them read only), replays the flags into it, sets its world border and spawn point on
     * those bounds, schedules the write phase of the overlay store at the end of every tick and
     * {@code EVT:chunk_stream_stats} every 60 s, and computes the far view announcement of those bounds.
     *
     * @param shutdown the shutdown sequence, still taking steps
     * @param events   where the events go, the event ledger of the host
     * @param flags    the world state flags in the directory mode ({@code API-M3-013}), null outside
     *                 it: nothing is then read or replayed
     * @return the map instance, the spawn point and the far view announcement
     * @throws IllegalArgumentException for a refused start
     */
    public Started start(ShutdownSequence shutdown, BiConsumer<String, Map<String, ?>> events,
                         @Nullable WorldFlagsImpl flags) {
        return start(shutdown, events, flags, OVERLAY_ROOT);
    }

    /**
     * {@link #start(ShutdownSequence, BiConsumer, WorldFlagsImpl)} with the overlay store elsewhere
     * than the working directory, for the tests.
     */
    Started start(ShutdownSequence shutdown, BiConsumer<String, Map<String, ?>> events, @Nullable WorldFlagsImpl flags,
                  Path overlayRoot) {
        return start(shutdown, events, flags, overlayRoot, null);
    }

    /**
     * {@link #start(ShutdownSequence, BiConsumer, WorldFlagsImpl, Path)} with the snapshot and the
     * computation of the light recomputations on {@code relightExecutor} instead of their own pool
     * ({@code SynxChunkLoader#startRelight(InstanceContainer, Executor)}), for the tests that hold a
     * recomputation back; null for the pool.
     */
    Started start(ShutdownSequence shutdown, BiConsumer<String, Map<String, ?>> events, @Nullable WorldFlagsImpl flags,
                  Path overlayRoot, @Nullable Executor relightExecutor) {
        // Before the overlay store is opened: flags that cannot be read refuse the start with the disk untouched
        if (flags != null) flags.load();
        final SynxSettings overlaySettings = overlaySettings(manifest, process.compressionThreshold());
        final Path overlayDirectory = overlayRoot.resolve(manifest.buildId());
        final OverlayStore overlay;
        try {
            overlay = OverlayStore.open(overlayRoot, manifest, overlaySettings, overlayOptions);
        } catch (SynxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException("the overlay store " + overlayDirectory + " cannot be opened: "
                    + e.getMessage(), e);
        }
        // The runtime light drains before the final flush writes the chunks: it is built with the instance,
        // below, and until then its step has nothing to wait for
        final RelightStep relightStep = new RelightStep();
        shutdown.addAfterPlayers("relight", relightStep::drain);
        shutdown.addAfterPlayers("overlay", overlay::finalFlush);
        final SynxManifest.Bounds bounds = bounds(manifest, directory);
        final SynxChunkLoader loader;
        try {
            loader = new SynxChunkLoader(settings.buildDir(), manifest, bounds, synxOptions, overlay, overlay);
        } catch (SynxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException("the map release " + manifest.buildId() + " cannot be opened: "
                    + e.getMessage(), e);
        }
        this.overlay = overlay;
        this.loader = loader;
        this.flags = flags;

        final InstanceContainer instance = process.instance().createInstanceContainer(dimensionType, loader);
        instance.setSimulationDistance(settings.simulationDistance());
        instance.setChunkUnloadGraceTicks(settings.chunkUnloadGraceTicks());
        final SynxRelight relight = relightExecutor == null ? loader.startRelight(instance)
                : loader.startRelight(instance, relightExecutor);
        relightStep.relight = relight;
        this.relight = relight;
        // Before the world border, the spawn point and the bind
        if (flags != null) flags.replay(instance);
        instance.setWorldBorder(worldBorder(bounds));
        if (bounds.maxX() - bounds.minX() != bounds.maxZ() - bounds.minZ()) {
            LOGGER.warn("The bounds {} of {} are not square: the world border covers the long side, a player can walk "
                    + "into the read only chunks past the short one", bounds, manifest.buildId());
        }
        final Pos spawn = spawn(settings.buildDir(), manifest, bounds);

        // One task at the end of the tick: the light first, whose installs make chunks dirty, then the write phase
        scheduleWritePhase(process, () -> {
            try {
                relight.installPhase();
            } catch (RuntimeException e) {
                process.exception().handleException(e);
            }
            overlay.tickEnd();
        });
        this.lastStream = loader.stats();
        this.lastOverlay = overlay.stats();
        this.lastRelight = relight.stats();
        this.lastFlagConflicts = flags == null ? 0 : flags.stats().conflicts();
        process.scheduler().buildTask(() -> recordStreamStats(events))
                .delay(STATS_INTERVAL).repeat(STATS_INTERVAL).schedule();
        LOGGER.info("Map mounted: build_id={} simulation_distance={} chunk_unload_grace_ticks={} spawn={} overlay={}",
                manifest.buildId(), settings.simulationDistance(), settings.chunkUnloadGraceTicks(), spawn, overlayDirectory);
        final FarViewAnnouncement farView = farViewAnnouncement(settings.buildDir(), manifest, bounds,
                settings.farviewUrlRoot());
        this.farViewTiles = farView == null ? 0 : farView.tiles().size();
        final MsgBuf encoded = MsgBuf.writing();
        if (farView != null) farView.write(encoded);
        return new Started(instance, spawn, relight, farView == null ? null : encoded.toByteArray());
    }

    /**
     * Runs the write phase of the overlay store at the end of every tick. A throw is reported to the
     * exception handler of the process and the next tick runs it again. The scheduler stops a repeating
     * task that throws, after which no dirty chunk would be written or unloaded again, and the final
     * flush that a stop with a live tick hands to this phase would never run.
     */
    static void scheduleWritePhase(ServerProcess process, Runnable writePhase) {
        process.scheduler().scheduleTask(() -> {
            try {
                writePhase.run();
            } catch (RuntimeException e) {
                process.exception().handleException(e);
            }
        }, TaskSchedule.nextTick(), TaskSchedule.nextTick(), ExecutionType.TICK_END);
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
        final WorldFlagsImpl flags = this.flags;
        final WorldFlagsImpl.Stats flagStats = flags == null ? null : flags.stats();
        attributes.put("flags_replayed", flagStats == null ? 0 : flagStats.replayed());
        attributes.put("flags_ms", flagStats == null ? 0 : flagStats.replayMillis());
        final ShardDirectory.Channel channel = directory == null ? null : directory.ownChannel();
        attributes.put("region_id", channel == null ? null : channel.regionId());
        attributes.put("capacity", channel == null ? null : channel.capacity());
        attributes.put("farview_tiles", farViewTiles);
        return attributes;
    }

    private void recordStreamStats(BiConsumer<String, Map<String, ?>> events) {
        final SynxChunkLoader loader = this.loader;
        final OverlayStore overlay = this.overlay;
        final SynxStreamStats lastStream = this.lastStream;
        final OverlayStats lastOverlay = this.lastOverlay;
        final SynxRelight relight = this.relight;
        final RelightStats lastRelight = this.lastRelight;
        if (loader == null || overlay == null || lastStream == null || lastOverlay == null || relight == null
                || lastRelight == null) return;
        final SynxStreamStats stream = loader.stats();
        final OverlayStats overlayStats = overlay.stats();
        final RelightStats relightStats = relight.stats();
        this.lastStream = stream;
        this.lastOverlay = overlayStats;
        this.lastRelight = relightStats;
        final Map<String, Object> attributes = streamStats(lastStream, stream, lastOverlay, overlayStats, lastRelight,
                relightStats);
        final WorldFlagsImpl flags = this.flags;
        if (flags != null) {
            final long conflicts = flags.stats().conflicts();
            attributes.put("flag_conflicts", conflicts - lastFlagConflicts);
            this.lastFlagConflicts = conflicts;
        }
        events.accept("chunk_stream_stats", attributes);
    }

    /**
     * {@code EVT:chunk_stream_stats} ({@code docs/DESIGN-M3.md} §20): the counters as the difference
     * of two readings, the current values and the window maxima as read.
     */
    static Map<String, Object> streamStats(SynxStreamStats before, SynxStreamStats now, OverlayStats overlayBefore,
                                           OverlayStats overlayNow, RelightStats relightBefore, RelightStats relightNow) {
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
        attributes.put("relight_queue_p99_ms", relightNow.queueMsP99());
        attributes.put("relight_ms_p99", relightNow.relightMsP99());
        attributes.put("relights", relightNow.relights() - relightBefore.relights());
        attributes.put("relight_skipped", relightNow.skipped() - relightBefore.skipped());
        attributes.put("light_materialized", relightNow.lightMaterialized() - relightBefore.lightMaterialized());
        attributes.put("light_packets", relightNow.lightPackets() - relightBefore.lightPackets());
        attributes.put("light_sections", relightNow.lightSections() - relightBefore.lightSections());
        attributes.put("relight_install_ms_max", relightNow.installMsMax());
        attributes.put("relight_pending", relightNow.pending());
        attributes.put("unloaded", now.unloaded() - before.unloaded());
        attributes.put("section_cache_bytes", now.sectionCacheBytes());
        // Without world state flags; the statistics task puts the difference of theirs in its place
        attributes.put("flag_conflicts", 0);
        return attributes;
    }

    /**
     * The shutdown step {@code relight}, registered before {@code overlay} when the overlay store opens,
     * which is before the runtime light exists: called before, it has nothing to drain.
     */
    private static final class RelightStep {
        volatile @Nullable SynxRelight relight;

        CompletableFuture<?> drain(Duration remaining, boolean tickAlive) {
            final SynxRelight current = relight;
            return current == null ? CompletableFuture.completedFuture(null) : current.drain(remaining, tickAlive);
        }
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
    static Pos spawn(Path buildDir, SynxManifest manifest, SynxManifest.Bounds bounds) {
        final int chunkX = centerChunk(bounds.minX(), bounds.maxX());
        final int chunkZ = centerChunk(bounds.minZ(), bounds.maxZ());
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
