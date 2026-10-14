package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.FileTrust;
import io.github.bearisbug.synaxis.app.ShutdownSequence;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack;
import io.github.bearisbug.synaxis.game.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.instance.Clock;
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
import io.github.bearisbug.synaxis.synx.VerifiedBuild;
import io.github.bearisbug.synaxis.timer.ExecutionType;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import io.github.bearisbug.synaxis.world.DimensionType;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * The map mounting mode of the shard host ({@code docs/DESIGN-M3.md} {@code API-M3-006}): checks the
 * map release, builds the map instance on {@link SynxChunkLoader} with the overlay store behind it,
 * and reports the streaming counters. {@code Main} drives it at three steps of its startup, (3)
 * {@link #prepare}, (5) {@link #start} and (7) {@link #startedAttributes}, and keeps the admission,
 * the spawn listener and the event ledger, which are the host's own.
 * <p>
 * Every refusal is an {@link IllegalArgumentException}, whose message a {@link SynxException} leads
 * with its synx code; {@code Main} turns it into {@code Refusing to start: …} and exit code 1. All of
 * them come before the first write to the disk, which is {@link OverlayStore#open}.
 */
public final class ShardWorld {
    /** The dimension type of the map instance, one whole string for the translation key gate. */
    public static final String DIMENSION_TYPE = "synaxis:map";
    /**
     * The largest chunk view distance a map shard serves: {@code view_bounds} reach one ring past it, the ring
     * the engine sends past the view distance.
     */
    static final int MAX_VIEW_DISTANCE = SynxChunkLoader.VIEW_MARGIN - 1;
    /** Where the overlay store lives, under the working directory. */
    static final Path OVERLAY_ROOT = Path.of("overlay");
    /** How often {@code EVT:chunk_stream_stats} is written. */
    static final Duration STATS_INTERVAL = Duration.ofSeconds(60);
    /**
     * §28.1, {@code API-M3-006}: where segment ② holds the {@code surface_y} of the spawn column, x 8 and z 8 of
     * the center chunk: after the walkable bit set, one little endian i16 per column by z × 16 + x.
     */
    private static final long SPAWN_SURFACE_Y_OFFSET = SynxFormat.NAVIGATION_SURFACE_Y + (8 * 16 + 8) * 2L;
    /** {@code API-M3-015}: the coarse prefixes of the tiles up to 256 chunks, 4,096 blocks, past the bounds are announced. */
    static final int COARSE_REACH = 256;
    /** {@code API-M3-002} 只读一次: the largest manifest read, 16 MiB. */
    static final int MAX_MANIFEST_BYTES = 16 << 20;
    /** The buffer a far view pack is read into while its digests are computed. */
    private static final int DIGEST_BUFFER_BYTES = 1 << 20;
    /** How {@link FileTrust} ends the refusal of an overlay directory or segment group or others may write. */
    private static final String OVERLAY_WRITER = "only the service user may write it";
    /**
     * {@code API-M3-006} 日夜: the wall clock's time is taken modulo 192,000 ticks, the period of the overworld's
     * {@code minecraft:moon} timeline, eight days of 24,000 ticks; modulo one day the moon would stay full.
     */
    private static final long DAY_CYCLE_TICKS = 192_000;
    /** A tick of the day in wall clock milliseconds, as the client counts it. */
    private static final long TICK_MILLIS = 50;
    /** How often, in ticks of the map instance, its clock is held against the wall clock. */
    private static final int CLOCK_CHECK_TICKS = 20;
    /** How far a running clock may stray from the wall clock before it is set back: one second. */
    private static final long CLOCK_TOLERANCE_TICKS = 20;

    private static final Logger LOGGER = LoggerFactory.getLogger(ShardWorld.class);

    private final ServerProcess process;
    private final ShardSettings settings;
    private final SynxOptions synxOptions;
    private final OverlayOptions overlayOptions;
    private final VerifiedBuild verified;
    private final SynxManifest manifest;
    private final RegistryKey<DimensionType> dimensionType;
    private final long verifyMillis;
    private final @Nullable ShardDirectory directory;
    // Set by #start, on the startup thread, before the process ticks
    private @Nullable SynxChunkLoader loader;
    private @Nullable OverlayStore overlay;
    private @Nullable WorldFlagsImpl flags;
    private @Nullable SynxRelight relight;
    private @Nullable ShardInstance instance;
    private int farViewTiles;
    private long overlayRecords;
    // First read by #start, then read and replaced by the statistics task only, on the tick scheduler thread
    private @Nullable SynxStreamStats lastStream;
    private @Nullable OverlayStats lastOverlay;
    private @Nullable RelightStats lastRelight;
    private long lastFlagConflicts;
    private long lastVoidChunks;

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
        final SynxManifest.Bounds reach = bounds.grow(COARSE_REACH);
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
     * {@code coarse_sha256} against its first {@code coarse_bytes} bytes. Who may have written the packs
     * was checked in step (3) ({@code API-M3-002} 文件信任). A url root of plain http to another host than
     * this one is announced all the same, with a WARN: it lets anyone on the way see which tiles a player
     * loads. Nothing is announced without a url root, when the manifest lists no pack (a release baked
     * before M3-G), without a tile meeting the bounds, when one pack does not match, when the tiles to
     * announce differ in {@code base_level} or {@code coarse_level}, or when they make no announcement (over
     * 64 of them, a url over 1,024 bytes); one line of the log says which, a WARN for the last three. None
     * of it refuses the start: the far view is optional.
     *
     * @return the announcement, null when there is none
     */
    static @Nullable FarViewAnnouncement farViewAnnouncement(Path buildDir, SynxManifest manifest,
                                                            SynxManifest.Bounds bounds, String urlRoot) {
        if (urlRoot.isEmpty()) {
            LOGGER.info("Far view off: {} is empty", ShardSettings.FARVIEW_URL_ROOT);
            return null;
        }
        if (manifest.farview().isEmpty()) {
            LOGGER.info("Far view off: {} has no far view packs (baker_version {}); rebake it for a far view",
                    manifest.buildId(), manifest.bakerVersion());
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
            final String file = packFile(pack);
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
                        urlRoot + "/" + SynxFormat.farViewObject(manifest.mapId(), digest)));
            }
            announcement = new FarViewAnnouncement(manifest.buildId(), first.baseLevel(), first.coarseLevel(),
                    manifest.world().minY(), manifest.world().height(), announced);
        } catch (MalformedMessageException e) {
            LOGGER.warn("Far view off: the far view tiles of {} make no announcement: {}", manifest.buildId(), e.getMessage());
            return null;
        }
        final long coarse = tiles.stream().filter(tile -> tile.layer() == FarViewAnnouncement.LAYER_COARSE).count();
        LOGGER.info("Far view: {} tile(s), {} coarse, from {}", tiles.size() - coarse, coarse, urlRoot);
        final String host = plainHttpHost(urlRoot);
        if (host != null && !loopback(host)) {
            LOGGER.warn("Far view packs go over plain http to {}: which tiles a player loads can be seen on the way, use https",
                    host);
        }
        return announcement;
    }

    /** The path of the pack of a tile under the release directory, {@code farview/t.<tx>.<tz>.hfp}. */
    static String packFile(SynxManifest.FarView pack) {
        return "farview/" + SynxFormat.farViewFileName(pack.tileX(), pack.tileZ());
    }

    /** How the pack in {@code file} differs from its manifest entry, empty when it does not. */
    private static String mismatch(Path file, SynxManifest.FarView pack, int layer) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return mismatch(channel, pack, layer);
        } catch (IOException e) {
            return "cannot be read: " + e;
        }
    }

    /**
     * {@link #mismatch(Path, SynxManifest.FarView, int)} on a channel of the pack (M3-S, {@code API-M3-015}
     * 宣告条): its size first, held to the manifest and to {@link FarViewAnnouncement#MAX_OBJECT_BYTES}
     * before a byte is read; then its SHA-256, and that of its first {@code coarse_bytes} bytes for a tile
     * announced by its coarse prefix, computed {@value #DIGEST_BUFFER_BYTES} bytes at a time on the same
     * channel.
     */
    static String mismatch(SeekableByteChannel channel, SynxManifest.FarView pack, int layer) throws IOException {
        final long size = channel.size();
        if (size != pack.bytes()) return "is " + size + " bytes, the manifest says " + pack.bytes();
        if (size > FarViewAnnouncement.MAX_OBJECT_BYTES) {
            return "is " + size + " bytes, over the " + FarViewAnnouncement.MAX_OBJECT_BYTES + " bytes a far view object may have";
        }
        final boolean coarse = layer == FarViewAnnouncement.LAYER_COARSE;
        final MessageDigest whole = SynxDigests.sha256();
        final MessageDigest prefix = SynxDigests.sha256();
        final ByteBuffer header = ByteBuffer.allocate(FarViewPack.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer buffer = ByteBuffer.allocate(DIGEST_BUFFER_BYTES);
        long read = 0;
        while (channel.read(buffer.clear()) >= 0) {
            buffer.flip();
            if (header.hasRemaining()) header.put(buffer.duplicate().limit(Math.min(buffer.limit(), header.remaining())));
            if (coarse && read < pack.coarseBytes()) {
                prefix.update(buffer.duplicate().limit((int) Math.min(buffer.limit(), pack.coarseBytes() - read)));
            }
            read += buffer.remaining();
            whole.update(buffer);
        }
        if (read != size) return "is " + read + " bytes read, " + size + " bytes long";
        if (!MessageDigest.isEqual(whole.digest(), pack.sha256())) return "does not match the sha256 of the manifest";
        if (!coarse) return "";
        if (header.hasRemaining()) return "is not a far view pack: shorter than its " + FarViewPack.HEADER_BYTES + " byte header";
        final long fineOffset = FarViewPack.fineOffset(header);
        if (fineOffset != pack.coarseBytes()) {
            return "has its coarse prefix in " + fineOffset + " bytes, the manifest says coarse_bytes " + pack.coarseBytes();
        }
        if (!MessageDigest.isEqual(prefix.digest(), pack.coarseSha256())) {
            return "has a coarse prefix that does not match the coarse_sha256 of the manifest";
        }
        return "";
    }

    /** The host of a url root of plain http, null for https. */
    static @Nullable String plainHttpHost(String urlRoot) {
        final String scheme = "http://";
        if (!urlRoot.regionMatches(true, 0, scheme, 0, scheme.length())) return null;
        String authority = urlRoot.substring(scheme.length());
        final int path = authority.indexOf('/');
        if (path >= 0) authority = authority.substring(0, path);
        authority = authority.substring(authority.lastIndexOf('@') + 1);
        if (authority.startsWith("[")) {
            final int end = authority.indexOf(']');
            return end < 0 ? authority : authority.substring(0, end + 1);
        }
        final int port = authority.indexOf(':');
        return port < 0 ? authority : authority.substring(0, port);
    }

    /** Whether a host names this machine without a lookup: {@code localhost}, 127.0.0.0/8 or ::1. */
    static boolean loopback(String host) {
        final String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return bare.equalsIgnoreCase("localhost") || bare.equals("::1") || bare.equals("0:0:0:0:0:0:0:1")
                || bare.matches("127\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}");
    }

    private ShardWorld(ServerProcess process, ShardSettings settings, SynxOptions synxOptions,
                       OverlayOptions overlayOptions, VerifiedBuild verified, RegistryKey<DimensionType> dimensionType,
                       long verifyMillis, @Nullable ShardDirectory directory) {
        this.process = process;
        this.settings = settings;
        this.synxOptions = synxOptions;
        this.overlayOptions = overlayOptions;
        this.verified = verified;
        this.manifest = verified.manifest();
        this.dimensionType = dimensionType;
        this.verifyMillis = verifyMillis;
        this.directory = directory;
    }

    /**
     * Step (3) of {@code Main}, after the match dimension type, while the registries still take new
     * entries: checks the view and simulation distances, checks who may have written the files of the
     * release before reading them ({@link MapFileTrust}), reads the manifest once, runs the start-up check
     * of {@code API-M3-002} on the region files it maps for good, registers the map dimension type from
     * the manifest's world with the overworld's timelines and clock, and the block handlers the baker
     * registered. In the directory mode it also checks the shard
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
        return prepare(process, settings, synxOptions, overlayOptions, directory, MapFileTrust::unixUid);
    }

    /** {@link #prepare(ServerProcess, ShardSettings, SynxOptions, OverlayOptions, ShardDirectory)} with the owners of the files read by {@code owners}, for the tests. */
    static ShardWorld prepare(ServerProcess process, ShardSettings settings, SynxOptions synxOptions,
                              OverlayOptions overlayOptions, @Nullable ShardDirectory directory, MapFileTrust.Owners owners) {
        final int viewDistance = process.config().chunkViewDistance();
        if (viewDistance > MAX_VIEW_DISTANCE) {
            throw new IllegalArgumentException("synaxis.chunk-view-distance=" + viewDistance + " is above "
                    + MAX_VIEW_DISTANCE + ", the most a map shard serves (view_bounds reach "
                    + SynxChunkLoader.VIEW_MARGIN + " chunks past its bounds)");
        }
        final int simulationDistance = settings.simulationDistance();
        if (simulationDistance < 1 || simulationDistance > viewDistance) {
            throw new IllegalArgumentException(ShardSettings.SIMULATION_DISTANCE + "=" + simulationDistance
                    + " is not from 1 to the chunk view distance " + viewDistance + ": " + (simulationDistance < 1
                    ? "a map shard always limits its simulation, -1 (no limit) is the engine's default only"
                    : "the simulation distance cannot exceed the view distance"));
        }
        final long started = System.nanoTime();
        final Path mapRoot = Objects.requireNonNull(settings.mapRoot(), "no map is mounted");
        final MapFileTrust trust = new MapFileTrust(mapRoot, owners);
        final VerifiedBuild verified;
        try {
            verified = verify(process, settings, directory, trust, mapRoot);
        } catch (SynxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        final SynxManifest manifest = verified.manifest();
        final long verifyMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        final SynxManifest.World world = manifest.world();
        // The overworld's timelines and clock: without the minecraft:day timeline the 26.2 client keeps the sun at noon
        final DimensionType overworld = Objects.requireNonNull(process.dimensionType().get(DimensionType.OVERWORLD),
                "minecraft:overworld");
        final RegistryKey<DimensionType> dimensionType = process.dimensionType().register(Key.key(DIMENSION_TYPE),
                DimensionType.builder().minY(world.minY()).height(world.height()).logicalHeight(world.height())
                        .timelines(overworld.timelines())
                        .defaultClock(Objects.requireNonNull(overworld.defaultClock(), "the overworld has a clock")).build());
        SynxHandlers.registerAll(process.block());
        LOGGER.info("Map release {} verified in {} ms: bounds={} world={}{}", manifest.buildId(), verifyMillis,
                manifest.bounds(), world, trust.checked() ? "" : " permissions=unchecked (the file system has no POSIX permissions)");
        return new ShardWorld(process, settings, synxOptions, overlayOptions, verified, dimensionType, verifyMillis,
                directory);
    }

    /**
     * The start-up check, in the order of {@code API-M3-002} 文件信任: who may have written the shard
     * directory, the release directory and the manifest before the manifest is read, once, on one
     * channel; who may have written the region files the shard maps and the far view packs it
     * announces before a byte of them is read; then the start-up check on the expectations of this
     * process (its registries and compression threshold, the light engine version, and the world the
     * manifest declares, which the map dimension type is then built on) over the mappings the shard
     * keeps. The scope is the {@code view_bounds} of the bounds the shard serves, its region in the
     * directory mode. A shard directory is checked against the manifest right after it is read.
     */
    private static VerifiedBuild verify(ServerProcess process, ShardSettings settings, @Nullable ShardDirectory directory,
                                        MapFileTrust trust, Path mapRoot) throws SynxException {
        final Path buildDir = settings.buildDir();
        final Path manifestFile = buildDir.resolve(SynxManifest.FILE_NAME);
        final Path directoryFile = mapRoot.resolve(ShardDirectory.FILE_NAME);
        if (directory != null) trust.check(directoryFile);
        trust.check(buildDir);
        trust.check(manifestFile);
        if (!Files.isRegularFile(manifestFile)) {
            // No release directory at all: synaxis.map.build is typed wrong, which redistributing (§14) does not fix
            final String release = Files.isDirectory(buildDir) ? ""
                    : "; no such release, " + mapRoot + " has " + releaseNames(mapRoot);
            throw new SynxException(SynxError.MANIFEST, SynxManifest.FILE_NAME, "missing in " + buildDir + release);
        }
        final SynxManifest declared;
        try (FileChannel channel = FileChannel.open(manifestFile, StandardOpenOption.READ)) {
            declared = readManifest(channel);
        } catch (IOException e) {
            throw new IllegalArgumentException(SynxError.MANIFEST.code() + ": " + SynxManifest.FILE_NAME
                    + ": cannot be read in " + buildDir + ": " + e, e);
        }
        if (directory != null) checkDirectory(directory, declared, directoryFile);
        final SynxManifest.Bounds bounds = bounds(declared, directory);
        final SynxManifest.Bounds scope = viewBounds(bounds);
        for (Path region : SynxBuildVerifier.regionFiles(buildDir, declared, scope)) trust.check(region);
        if (!settings.farviewUrlRoot().isEmpty()) {
            for (AnnouncedTile tile : announcedTiles(declared.farview(), bounds)) {
                trust.check(buildDir.resolve(packFile(tile.pack())));
            }
        }
        final SynxManifest.World world = declared.world();
        final SynxExpectations expectations = SynxExpectations.of(process.registries(), process.compressionThreshold(),
                world.minY(), world.height(), LightEngine.VERSION);
        try {
            return SynxBuildVerifier.open(buildDir, declared, expectations, scope);
        } catch (IOException e) {
            throw new IllegalArgumentException("the map release " + declared.buildId() + " cannot be opened: " + e, e);
        }
    }

    /**
     * The release directories under {@code mapRoot} by name, those starting with a dot left out, for a
     * {@code synaxis.map.build} that names none of them. Only the names are read.
     */
    private static String releaseNames(Path mapRoot) {
        try (Stream<Path> entries = Files.list(mapRoot)) {
            return entries.filter(Files::isDirectory).map(entry -> entry.getFileName().toString())
                    .filter(name -> !name.startsWith(".")).sorted().toList().toString();
        } catch (IOException e) {
            return "nothing that can be listed: " + e;
        }
    }

    /**
     * The manifest from a channel of {@code manifest.json} ({@code API-M3-002} 只读一次): at most
     * {@value #MAX_MANIFEST_BYTES} bytes, its size compared before a byte is read, the rest as
     * {@link SynxManifest#fromJson} reads it.
     *
     * @throws SynxException {@link SynxError#MANIFEST} for a larger file or a manifest that does not read
     * @throws IOException   if the channel cannot be read, or the file is not UTF-8
     */
    static SynxManifest readManifest(SeekableByteChannel channel) throws IOException, SynxException {
        final byte[] bytes = BoundedRead.readAtMost(channel, MAX_MANIFEST_BYTES);
        if (bytes == null) {
            throw new SynxException(SynxError.MANIFEST, SynxManifest.FILE_NAME, channel.size() + " bytes, over "
                    + MAX_MANIFEST_BYTES);
        }
        return SynxManifest.fromJson(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
    }

    /**
     * The shard directory against the manifest it serves ({@code API-M3-010}), then the spawn column
     * against the gates of the region. The directory does not know its file, so the prefix every
     * refusal about it starts with goes in front of the detail {@link ShardDirectory#checkAgainst} gives.
     */
    private static void checkDirectory(ShardDirectory directory, SynxManifest declared, Path directoryFile) {
        try {
            directory.checkAgainst(declared);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(ShardDirectory.refusal(directoryFile.toAbsolutePath()) + e.getMessage(), e);
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
        final int x = spawnColumn(bounds.minX(), bounds.maxX());
        final int z = spawnColumn(bounds.minZ(), bounds.maxZ());
        for (ShardDirectory.Gate gate : region.gates()) {
            final ShardDirectory.Frame frame = gate.frame();
            if (x >= frame.minX() && x <= frame.maxX() && z >= frame.minZ() && z <= frame.maxZ()) {
                throw new IllegalArgumentException("the spawn column " + x + ", " + z + " of " + region.regionId()
                        + " is inside the gate " + gate.gateId() + ": a player would be sent away on joining");
            }
        }
    }

    /** The {@code map_id} of the release. */
    public String mapId() {
        return manifest.mapId();
    }

    /** The {@code build_id} of the release. */
    public String buildId() {
        return manifest.buildId();
    }

    /**
     * The bounds the shard serves in chunks, both ends included, which step (5) of {@code API-M3-006} sets
     * the world border from; known from step (3) on.
     */
    public SynxManifest.Bounds bounds() {
        return bounds(manifest, directory);
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

    /** §28.1: the block column players spawn on, on one axis: the center column of the center chunk of the bounds. */
    private static int spawnColumn(int min, int max) {
        return centerChunk(min, max) * 16 + 8;
    }

    static SynxManifest.Bounds viewBounds(SynxManifest.Bounds bounds) {
        return bounds.grow(SynxChunkLoader.VIEW_MARGIN);
    }

    /**
     * Step (5) of {@code Main}: reads the world state flags (a refusal still, before the first write),
     * opens the overlay store (the first write to the disk, after every other refusal) and registers
     * its final flush after the players' save, builds the map instance with its simulation distance
     * and unload grace period on the bounds the shard serves (its region in the directory mode, the
     * chunks past them read only), replays the flags into it, sets its world border and spawn point on
     * those bounds, sets its clock to the wall clock and holds it there ({@code API-M3-006} 日夜),
     * schedules the write phase of the overlay store at the end of every tick and
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
        return start(shutdown, events, flags, overlayRoot, relightExecutor, System::currentTimeMillis);
    }

    /**
     * {@link #start(ShutdownSequence, BiConsumer, WorldFlagsImpl, Path, Executor)} on the wall clock
     * {@code wallMillis} (epoch milliseconds) instead of the system's, for the tests of the day.
     */
    Started start(ShutdownSequence shutdown, BiConsumer<String, Map<String, ?>> events, @Nullable WorldFlagsImpl flags,
                  Path overlayRoot, @Nullable Executor relightExecutor, LongSupplier wallMillis) {
        // Before the overlay store is opened: flags that cannot be read refuse the start with the disk untouched
        if (flags != null) flags.load();
        final SynxSettings overlaySettings = overlaySettings(manifest, process.compressionThreshold());
        final Path overlayDirectory = overlayRoot.resolve(manifest.buildId());
        // Before the store opens and scans them: the overlay is read back as the map is (API-M3-002 覆盖层)
        checkOverlay(overlayRoot, overlayDirectory);
        final OverlayStore overlay;
        try {
            overlay = OverlayStore.open(overlayRoot, manifest, overlaySettings, overlayOptions);
        } catch (SynxException e) {
            // The dispositions of §14 for these codes are about the release directory, not this one
            throw new IllegalArgumentException(e.getMessage() + " (in the overlay store " + overlayDirectory + ")", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("the overlay store " + overlayDirectory + " cannot be opened: " + e, e);
        }
        // The runtime light drains before the final flush writes the chunks: it is built with the instance,
        // below, and until then its step has nothing to wait for
        final RelightStep relightStep = new RelightStep();
        shutdown.addAfterPlayers("relight", relightStep::drain);
        shutdown.addAfterPlayers("overlay", overlay::finalFlush);
        final SynxManifest.Bounds bounds = bounds(manifest, directory);
        final SynxChunkLoader loader = new SynxChunkLoader(verified, bounds, synxOptions, overlay, overlay);
        this.overlay = overlay;
        this.loader = loader;
        this.flags = flags;

        // Built as createInstanceContainer builds one, counting the chunks asked for outside view_bounds (M3-S)
        final ShardInstance instance = new ShardInstance(process, dimensionType, loader, viewBounds(bounds));
        process.instance().registerInstance(instance);
        this.instance = instance;
        instance.setSimulationDistance(settings.simulationDistance());
        instance.setChunkUnloadGraceTicks(settings.chunkUnloadGraceTicks());
        final SynxRelight relight = loader.startRelight(instance, relightExecutor);
        relightStep.relight = relight;
        this.relight = relight;
        // Before the world border and the spawn point below, and before Main binds the port in step (6)
        if (flags != null) flags.replay(instance);
        instance.setWorldBorder(worldBorder(bounds));
        if (bounds.maxX() - bounds.minX() != bounds.maxZ() - bounds.minZ()) {
            LOGGER.warn("The bounds {} of {} are not square: the world border covers the long side, players walk past the "
                    + "short one until the border turns them back", bounds, manifest.buildId());
        }
        final Pos spawn = spawn(verified, bounds);
        startDay(instance, wallMillis);
        if (synxOptions.worldWritable()) {
            LOGGER.warn("Map {} is writable ({}=true): players' digging and placing go into the overlay", manifest.buildId(),
                    SynxOptions.WORLD_WRITABLE);
        }

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
        final OverlayStats overlayStats = overlay.stats();
        this.lastOverlay = overlayStats;
        // EVT:shard_started takes the records from here: a read once the process ticks would take the
        // window maxima of the overlay store away from the first EVT:chunk_stream_stats
        this.overlayRecords = overlayStats.records();
        this.lastRelight = relight.stats();
        this.lastFlagConflicts = flags == null ? 0 : flags.stats().conflicts();
        this.lastVoidChunks = instance.voidChunks();
        process.scheduler().buildTask(() -> recordStreamStats(events))
                .delay(STATS_INTERVAL).repeat(STATS_INTERVAL).schedule();
        LOGGER.info("Map mounted: build_id={} simulation_distance={} chunk_unload_grace_ticks={} spawn={} overlay={} "
                + "overlay_cap={} flush_interval_s={} overlay_max_bytes={} relight_threads={} resend_light={} "
                + "section_cache_bytes={}", manifest.buildId(), settings.simulationDistance(),
                settings.chunkUnloadGraceTicks(), spawn, overlayDirectory, overlayOptions.cap(),
                overlayOptions.flushIntervalSeconds(), overlayOptions.maxBytes(), synxOptions.relightThreads(),
                synxOptions.resendLight(), synxOptions.sectionCacheBytes());
        final FarViewAnnouncement farView = farViewAnnouncement(settings.buildDir(), manifest, bounds,
                settings.farviewUrlRoot());
        this.farViewTiles = farView == null ? 0 : farView.tiles().size();
        final MsgBuf encoded = MsgBuf.writing();
        if (farView != null) farView.write(encoded);
        return new Started(instance, spawn, relight, farView == null ? null : encoded.toByteArray());
    }

    /**
     * Runs {@code tickEnd} at the end of every tick: in a map shard the install phase of the runtime light,
     * then the write phase of the overlay store ({@link #start}). A throw is reported to the exception
     * handler of the process and the next tick runs it again. The scheduler stops a repeating task that
     * throws, after which no dirty chunk would be written or unloaded again, and the final flush that a
     * stop with a live tick hands to the write phase would never run.
     */
    static void scheduleWritePhase(ServerProcess process, Runnable tickEnd) {
        process.scheduler().scheduleTask(() -> {
            try {
                tickEnd.run();
            } catch (RuntimeException e) {
                process.exception().handleException(e);
            }
        }, TaskSchedule.nextTick(), TaskSchedule.nextTick(), ExecutionType.TICK_END);
    }

    /**
     * {@code API-M3-006} 日夜: sets the clock of the map instance to the wall clock, a tick of the day every tick, and
     * holds it there every {@value #CLOCK_CHECK_TICKS} ticks at the end of the instance's tick, after the clock
     * ticked, on the thread that ticks it.
     */
    private static void startDay(InstanceContainer instance, LongSupplier wallMillis) {
        final Clock clock = Objects.requireNonNull(instance.defaultClock(), "the map has a clock");
        clock.rate(1f);
        clock.time(wallTime(wallMillis.getAsLong()));
        instance.scheduler().buildTask(() -> alignClock(clock, wallMillis.getAsLong()))
                .delay(TaskSchedule.tick(CLOCK_CHECK_TICKS)).repeat(TaskSchedule.tick(CLOCK_CHECK_TICKS))
                .executionType(ExecutionType.TICK_END).schedule();
    }

    /** {@code API-M3-006} 日夜: the time of every shard of a map at {@code epochMillis}, (epoch ms ÷ 50) mod 192,000. */
    static long wallTime(long epochMillis) {
        return Math.floorMod(Math.floorDiv(epochMillis, TICK_MILLIS), DAY_CYCLE_TICKS);
    }

    /**
     * Sets a running {@code clock} back to the wall clock once it strayed {@value #CLOCK_TOLERANCE_TICKS} ticks or
     * more, which a tick loop that gives up catching up does by dropping ticks; {@link Clock#time(long)} sends the
     * players of the instance the time. A stopped clock is left where it is: only a test fixture stops one.
     */
    static void alignClock(Clock clock, long epochMillis) {
        if (clock.paused()) return;
        final long target = wallTime(epochMillis);
        final long ahead = Math.floorMod(clock.time() - target, DAY_CYCLE_TICKS);
        if (Math.min(ahead, DAY_CYCLE_TICKS - ahead) >= CLOCK_TOLERANCE_TICKS) clock.time(target);
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
     * What {@code EVT:shard_started} carries, which {@code Main} writes once the port is bound: the records of
     * the overlay store as {@link #start} read them before the process ticked, and the overlay and light
     * settings in effect, the denominators of the overlay SLIs of §21.
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
        attributes.put("overlay_records", overlayRecords);
        final WorldFlagsImpl flags = this.flags;
        final WorldFlagsImpl.Stats flagStats = flags == null ? null : flags.stats();
        attributes.put("flags_replayed", flagStats == null ? 0 : flagStats.replayed());
        attributes.put("flags_ms", flagStats == null ? 0 : flagStats.replayMillis());
        final ShardDirectory.Channel channel = directory == null ? null : directory.ownChannel();
        attributes.put("region_id", channel == null ? null : channel.regionId());
        attributes.put("capacity", channel == null ? null : channel.capacity());
        attributes.put("farview_tiles", farViewTiles);
        attributes.put("world_writable", synxOptions.worldWritable());
        attributes.put("overlay_cap", overlayOptions.cap());
        attributes.put("overlay_max_bytes", overlayOptions.maxBytes());
        attributes.put("relight_threads", synxOptions.relightThreads());
        return attributes;
    }

    private void recordStreamStats(BiConsumer<String, Map<String, ?>> events) {
        final SynxChunkLoader loader = this.loader;
        final OverlayStore overlay = this.overlay;
        final SynxStreamStats streamBefore = this.lastStream;
        final OverlayStats overlayBefore = this.lastOverlay;
        final SynxRelight relight = this.relight;
        final RelightStats relightBefore = this.lastRelight;
        final ShardInstance instance = this.instance;
        if (loader == null || overlay == null || streamBefore == null || overlayBefore == null || relight == null
                || relightBefore == null || instance == null) return;
        final SynxStreamStats stream = loader.stats();
        final OverlayStats overlayNow = overlay.stats();
        final RelightStats relightNow = relight.stats();
        final long voidChunks = instance.voidChunks();
        final Map<String, Object> attributes = streamStats(streamBefore, stream, overlayBefore, overlayNow,
                relightBefore, relightNow, voidChunks - lastVoidChunks);
        this.lastStream = stream;
        this.lastOverlay = overlayNow;
        this.lastRelight = relightNow;
        this.lastVoidChunks = voidChunks;
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
     *
     * @param voidChunks the requests for a chunk outside {@code view_bounds} between the two readings (M3-S)
     */
    static Map<String, Object> streamStats(SynxStreamStats before, SynxStreamStats now, OverlayStats overlayBefore,
                                           OverlayStats overlayNow, RelightStats relightBefore, RelightStats relightNow,
                                           long voidChunks) {
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
        attributes.put("void_chunks", voidChunks);
        attributes.put("overlay_bytes", overlayNow.writtenBytes());
        attributes.put("overlay_full", overlayNow.full());
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
     * its {@code surface_y} of segment ②, read from the mapping the start-up check made. A column without
     * a block to stand on, whose {@code surface_y} is {@code min_y}, keeps the spawn at the bottom of the
     * world with a WARN; a center chunk without a readable record puts it at the top of the world, with a
     * WARN that says why: no region file, no record, or the fault of a record that does not read.
     */
    static Pos spawn(VerifiedBuild verified, SynxManifest.Bounds bounds) {
        final SynxManifest manifest = verified.manifest();
        final int chunkX = centerChunk(bounds.minX(), bounds.maxX());
        final int chunkZ = centerChunk(bounds.minZ(), bounds.maxZ());
        final int columnX = spawnColumn(bounds.minX(), bounds.maxX()), columnZ = spawnColumn(bounds.minZ(), bounds.maxZ());
        final double x = columnX + 0.5, z = columnZ + 0.5;
        final SynxRegion region = verified.region(SynxFormat.region(chunkX), SynxFormat.region(chunkZ));
        final SynxSlot slot = region == null ? null : region.slot(chunkX, chunkZ);
        if (slot instanceof SynxSlot.Present present) {
            final short surfaceY = present.navigation().get(
                    ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), SPAWN_SURFACE_Y_OFFSET);
            // The baker writes min_y for a column without a block that blocks motion, and only for one
            if (surfaceY == manifest.world().minY()) {
                LOGGER.warn("The center column {},{} of {} has no block to stand on, players spawn at the bottom of the "
                        + "world, y {}", columnX, columnZ, manifest.buildId(), surfaceY);
            }
            return new Pos(x, surfaceY, z);
        }
        final int top = manifest.world().minY() + manifest.world().height();
        // The offset index is not under payload_sha256, so a damaged entry passes the start-up check
        final String why = slot == null ? "no region file"
                : slot instanceof SynxSlot.Failed failed ? failed.fault() + ": " + failed.detail() : "no record";
        LOGGER.warn("The center chunk {},{} of {} has no readable record ({}), players spawn at the top of the world, y {}",
                chunkX, chunkZ, manifest.buildId(), why, top);
        return new Pos(x, top, z);
    }

    /**
     * {@code API-M3-002} 覆盖层: the overlay store, written by the shard itself, is read back and sent to
     * the players as the map is. So {@code overlay/}, {@code overlay/<build_id>/} and its segment files,
     * those that are there, go through {@link FileTrust} by their real paths before the store opens: one of
     * them group or others may write, sticky bit or not, or one whose directory they may write without the
     * sticky bit, refuses the start. The owner is not looked at: the working directory is the service
     * user's alone ({@code docs/DESIGN-PROD.md} §23). A file system without the {@code unix} attribute view
     * is not checked.
     *
     * @throws IllegalArgumentException for a refused start, which starts with the real path
     */
    static void checkOverlay(Path overlayRoot, Path overlayDirectory) {
        try {
            checkOverlayPath(overlayRoot);
            checkOverlayPath(overlayDirectory);
            if (!Files.isDirectory(overlayDirectory)) return;
            try (DirectoryStream<Path> segments = Files.newDirectoryStream(overlayDirectory, "seg.*.synx")) {
                for (Path segment : segments) checkOverlayPath(segment);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("the overlay store " + overlayDirectory + " cannot be opened: " + e, e);
        }
    }

    private static void checkOverlayPath(Path path) throws IOException {
        if (!Files.exists(path)) return;
        final Path real = path.toRealPath();
        if (!real.getFileSystem().supportedFileAttributeViews().contains("unix")) return;
        final String writable = FileTrust.writableByOthers(real, OVERLAY_WRITER);
        if (writable != null) throw new IllegalArgumentException(real + ": " + writable);
    }
}
