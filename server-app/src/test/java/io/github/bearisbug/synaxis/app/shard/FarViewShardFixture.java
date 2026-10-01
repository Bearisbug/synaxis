package io.github.bearisbug.synaxis.app.shard;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.ShutdownParticipant;
import io.github.bearisbug.synaxis.app.ShutdownSequence;
import io.github.bearisbug.synaxis.baker.Baker;
import io.github.bearisbug.synaxis.baker.GeneratorPreset;
import io.github.bearisbug.synaxis.baker.GeneratorSource;
import io.github.bearisbug.synaxis.baker.LightVersions;
import io.github.bearisbug.synaxis.baker.SyntheticTerrain;
import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.event.player.PlayerSpawnEvent;
import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Layer;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Sample;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.generator.GenerationUnit;
import io.github.bearisbug.synaxis.instance.generator.UnitModifier;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.synx.OverlayOptions;
import io.github.bearisbug.synaxis.synx.SynxDigests;
import io.github.bearisbug.synaxis.synx.SynxException;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.synx.SynxOptions;
import io.github.bearisbug.synaxis.world.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The far view fixture of M3-G ({@code docs/DESIGN-M3.md} §26 M3-G step 0 and 容器验收): a map shard
 * assembled as {@link LightShardFixture} assembles one, plus an HTTP server for the height field packs
 * it announces, which the real client of the headless container (or a real machine) downloads and
 * bakes into Distant Horizons.
 * <p>
 * {@code main(port, httpPort, workDir, mode[, urlRoot])}: the game on {@code 0.0.0.0:<port>}, the
 * packs on {@code 0.0.0.0:<httpPort>} under {@code /<map_id>/farview/<sha256>.hfp}, every connection
 * sharing one bucket of {@value #BYTES_PER_SECOND} B/s (50 Mbit/s). {@code urlRoot} is the root of the
 * announced URLs, {@code http://host.docker.internal:<httpPort>} unless given: what a client in the
 * container sees. {@code /expect.json} gives the points to check in the library and the camera spots,
 * {@code /access.log} one line per pack request: method, path, {@code Range} header ({@code -} without),
 * status, and {@code cut} for a response cut short. Prints {@value #READY} once both listen.
 * <p>
 * The modes:
 * <ul>
 * <li>{@code synthetic}: a small map baked from the {@link LightMapFixture} generator on its 33 × 33
 * chunks, {@code min_y} -64 and 256 high (what it builds above y 191 dropped, {@link #generateBelowTop}), and
 * packs written straight from {@link Synthetic}: tile
 * (0, 0) whole, the eight around it as coarse prefixes. The fixture's own configuration listener
 * announces them, on the first configuration of a connection.</li>
 * <li>{@code break-sha}: as {@code synthetic}, the whole pack of tile (0, 0) served with one byte
 * changed, so it never matches its digest.</li>
 * <li>{@code half}: as {@code synthetic}, the first pack request answered with half its bytes and the
 * connection then closed.</li>
 * <li>{@code baked}: the baker's synthetic terrain over 3 × 3 tiles ({@code --bounds -256,-256,511,511}),
 * baked once into {@code workDir} and reused, mounted through a shard directory of one region, tile
 * (0, 0), and announced by {@link ShardWorld} itself: whatever the baker put into {@code farview[]}.</li>
 * </ul>
 */
final class FarViewShardFixture {
    static final String READY = "farview fixture ready";
    /** The map of {@code synthetic}, {@code break-sha} and {@code half}. */
    static final String MAP_ID = "farview";
    /** The map of {@code baked}. */
    static final String BAKED_MAP_ID = "farview3x3";
    static final int MIN_Y = -64;
    static final int HEIGHT = 256;
    /** The download rate of every connection together: 50 Mbit/s ({@code docs/DESIGN-M3.md} §15). */
    static final long BYTES_PER_SECOND = 6_250_000;
    /** The 3 × 3 tiles of {@code baked} and the one region the shard serves, tile (0, 0). */
    static final SynxManifest.Bounds BAKED_BOUNDS = new SynxManifest.Bounds(-256, -256, 511, 511);
    static final SynxManifest.Bounds BAKED_REGION = new SynxManifest.Bounds(0, 0, 255, 255);

    private static final Pattern PACK_PATH = Pattern.compile("/([a-z0-9_-]+)/farview/([0-9a-f]{64})\\.hfp");
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d*)");

    private FarViewShardFixture() {
    }

    /** The player store of a shard nobody is saved in. */
    private static final class NoPlayers implements ShutdownParticipant {
        @Override
        public void onGateClosed(Duration countdown) {
        }

        @Override
        public CompletableFuture<?> saveAll(Duration budget, boolean tickAlive) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onSaveEnded(boolean timedOut) {
        }
    }

    /**
     * @param args the game port, the HTTP port, the working directory the map is baked into and the
     *             overlay store kept in, the mode, and the root of the announced URLs if not the default
     */
    public static void main(String[] args) throws IOException {
        final int port = Integer.parseInt(args[0]);
        final int httpPort = Integer.parseInt(args[1]);
        final Path workDir = Path.of(args[2]);
        final String mode = args[3];
        final String urlRoot = args.length > 4 ? args[4] : "http://host.docker.internal:" + httpPort;
        if (!List.of("synthetic", "baked", "break-sha", "half").contains(mode)) {
            throw new IllegalArgumentException("mode is synthetic, baked, break-sha or half, not " + mode);
        }
        // What ShardMain writes, where the command line said nothing
        final int cores = Runtime.getRuntime().availableProcessors();
        setIfAbsent("synaxis.thread-provider", "grid");
        setIfAbsent("synaxis.dispatcher-threads", Integer.toString(Math.max(1, cores - 2)));
        setIfAbsent("synaxis.chunk-view-distance", "12");
        setIfAbsent("synaxis.chunk-queue.max-per-tick", "4");
        setIfAbsent("synaxis.max-visible-entities-per-player", "100");

        final boolean baked = mode.equals("baked");
        final Path mapsRoot = workDir.resolve("maps");
        final ShardSettings release = baked
                ? release(mapsRoot, BAKED_MAP_ID, new GeneratorPreset(new SyntheticTerrain(0), 0, SyntheticTerrain.HEIGHT,
                BAKED_BOUNDS), Math.min(cores, 3))
                : release(mapsRoot, MAP_ID, new GeneratorPreset(FarViewShardFixture::generateBelowTop, MIN_Y, HEIGHT, LightMapFixture.BOUNDS),
                Math.max(1, cores / 2));
        final ShardSettings settings = new ShardSettings(release.mapRoot(), release.mapBuild(), 4, 100,
                baked ? urlRoot : "");
        final ShardDirectory directory = baked ? new ShardDirectory(BAKED_MAP_ID,
                List.of(new ShardDirectory.Region("r0", BAKED_REGION, List.of())),
                List.of(new ShardDirectory.Channel("r0-c1", "r0", 1, "127.0.0.1", port, 500)), "r0-c1") : null;

        final MinecraftServer server = MinecraftServer.init();
        final ServerProcess process = server.process();
        final ShardWorld world = ShardWorld.prepare(process, settings, SynxOptions.fromSystemProperties(),
                OverlayOptions.fromSystemProperties(), directory);
        final ShutdownSequence shutdown = new ShutdownSequence(process, new NoPlayers());
        process.setShutdownParticipant(shutdown);
        final ShardWorld.Started started = world.start(shutdown, (_, _) -> {
        }, null, workDir.resolve("overlay"));
        final InstanceContainer instance = started.instance();
        final Pos spawn = started.spawn();
        final SynxManifest manifest;
        try {
            manifest = SynxManifest.fromJson(Files.readString(settings.buildDir().resolve(SynxManifest.FILE_NAME)));
        } catch (SynxException e) {
            throw new IllegalStateException(e);
        }

        final Content content = baked ? Content.baked(settings.buildDir(), manifest, started.farView())
                : Content.synthetic(manifest.buildId(), urlRoot, mode.equals("break-sha"));
        final byte[] announcement = content.announcement();
        process.eventHandler().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(spawn);
            if (announcement != null && event.isFirstConfig()) {
                event.getPlayer().sendPluginMessage(FarViewAnnouncement.CHANNEL, announcement);
            }
        });
        process.eventHandler().addListener(PlayerSpawnEvent.class, event -> {
            if (event.isFirstSpawn()) event.getPlayer().setGameMode(GameMode.CREATIVE);
        });

        final PackServer packs = new PackServer(content.mapId(), content.served(), mode.equals("half"),
                expectJson(mode, content, spawn));
        packs.start(httpPort);
        server.start("0.0.0.0", port);
        if (announcement != null) {
            for (FarViewAnnouncement.Tile tile : FarViewAnnouncement.read(MsgBuf.reading(announcement)).tiles()) {
                System.out.println("farview fixture: tile " + tile.tileX() + " " + tile.tileZ() + " layer " + tile.layer()
                        + " bytes " + tile.bytes() + " sha256 " + SynxDigests.hex(tile.sha256()) + " url " + tile.url());
            }
        }
        System.out.println("farview fixture: mode=" + mode + " build_id=" + manifest.buildId() + " objects="
                + content.served().size() + " announced=" + (announcement == null ? "none" : content.describe())
                + " http=0.0.0.0:" + packs.port());
        System.out.println(READY);
    }

    /**
     * {@link LightMapFixture#generate} in a world whose top is y {@value #MIN_Y} + {@value #HEIGHT} - 1 = 191: the
     * light map builds up to y 251 (the tower of chunk 20,20), and whatever it sets at the top of the world
     * or above is dropped. A pack's y index is one byte, so 256 is as high as a far view world goes.
     */
    static void generateBelowTop(GenerationUnit unit) {
        LightMapFixture.generate(new BelowTop(unit));
    }

    /** A unit whose modifier drops the blocks set at y {@code top} or above. */
    private record BelowTop(GenerationUnit unit) implements GenerationUnit {
        @Override
        public UnitModifier modifier() {
            final UnitModifier modifier = unit.modifier();
            final int top = unit.absoluteEnd().blockY();
            return new UnitModifier() {
                @Override
                public void setBlock(int x, int y, int z, Block block) {
                    if (y < top) modifier.setBlock(x, y, z, block);
                }

                @Override
                public void setBiome(int x, int y, int z, RegistryKey<Biome> biome) {
                    modifier.setBiome(x, y, z, biome);
                }

                @Override
                public void setRelative(int x, int y, int z, Block block) {
                    modifier.setRelative(x, y, z, block);
                }

                @Override
                public void setAll(Supplier supplier) {
                    modifier.setAll(supplier);
                }

                @Override
                public void setAllRelative(Supplier supplier) {
                    modifier.setAllRelative(supplier);
                }

                @Override
                public void fill(Block block) {
                    modifier.fill(block);
                }

                @Override
                public void fill(Point start, Point end, Block block) {
                    modifier.fill(start, end, block);
                }

                @Override
                public void fillHeight(int minHeight, int maxHeight, Block block) {
                    modifier.fillHeight(minHeight, Math.min(maxHeight, top), block);
                }

                @Override
                public void fillBiome(RegistryKey<Biome> biome) {
                    modifier.fillBiome(biome);
                }
            };
        }

        @Override
        public BlockVec size() {
            return unit.size();
        }

        @Override
        public BlockVec absoluteStart() {
            return unit.absoluteStart();
        }

        @Override
        public BlockVec absoluteEnd() {
            return unit.absoluteEnd();
        }

        @Override
        public GenerationUnit fork(Point start, Point end) {
            return unit.fork(start, end);
        }

        @Override
        public void fork(Consumer<Block.Setter> consumer) {
            unit.fork(consumer);
        }
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) System.setProperty(key, value);
    }

    /** The release of {@code mapId} under {@code mapsRoot}: the one published there, or baked now. */
    static ShardSettings release(Path mapsRoot, String mapId, GeneratorPreset preset, int threads) {
        final Path mapRoot = mapsRoot.resolve(mapId);
        if (Files.isDirectory(mapRoot)) {
            try (Stream<Path> builds = Files.list(mapRoot)) {
                final Optional<Path> published = builds.filter(build -> Files.isRegularFile(build.resolve(SynxManifest.FILE_NAME)))
                        .findFirst();
                if (published.isPresent()) {
                    System.out.println("farview fixture: reusing " + published.get());
                    return new ShardSettings(mapRoot, published.get().getFileName().toString(), 4, 100);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        final long started = System.nanoTime();
        final Baker.Result result = Baker.bake(new Baker.Request(mapId, new GeneratorSource(preset, Registries.vanilla()),
                mapsRoot, threads, mapsRoot.resolve("logs"), Registries.vanilla(), _ -> null, LightVersions.verified(), 1));
        if (result.exitCode() != Baker.EXIT_PUBLISHED || result.directory() == null)
            throw new AssertionError("the map " + mapId + " did not bake: " + result);
        System.out.println("farview fixture: baked " + result.directory() + " with " + threads + " thread(s) in "
                + TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) + " s");
        return new ShardSettings(mapRoot, result.directory().getFileName().toString(), 4, 100);
    }

    /**
     * What the fixture serves and announces.
     *
     * @param mapId        the map of the URLs
     * @param served       the objects by the lower case hex of the SHA-256 they are announced under,
     *                     with the bytes served for each (one changed byte in {@code break-sha})
     * @param announcement the encoded announcement, null when there is none
     * @param packs        the packs as announced, by {@code "<tile_x> <tile_z>"}: the whole pack of a
     *                     tile announced whole, the coarse prefix of the others
     * @param views        the camera spots of V1 ⑥ and V3b, each {@code x, y, z, yaw, pitch}
     */
    record Content(String mapId, Map<String, byte[]> served, byte @Nullable [] announcement,
                   Map<String, byte[]> packs, Map<String, double[]> views) {

        static Content synthetic(String buildId, String urlRoot, boolean breakSha) {
            final Map<String, byte[]> served = new TreeMap<>();
            final Map<String, byte[]> packs = new TreeMap<>();
            final List<FarViewAnnouncement.Tile> tiles = new ArrayList<>();
            for (int tileX = -1; tileX <= 1; tileX++) {
                for (int tileZ = -1; tileZ <= 1; tileZ++) {
                    final boolean whole = tileX == 0 && tileZ == 0;
                    final byte[] pack = whole ? Synthetic.wholePack() : Synthetic.coarsePack(tileX, tileZ);
                    final byte[] digest = SynxDigests.sha256(pack);
                    final String hex = SynxDigests.hex(digest);
                    final byte[] servedBytes = pack.clone();
                    if (whole && breakSha) servedBytes[servedBytes.length / 2] ^= 0x01;
                    served.put(hex, servedBytes);
                    packs.put(tileX + " " + tileZ, pack);
                    tiles.add(new FarViewAnnouncement.Tile(tileX, tileZ, whole ? FarViewAnnouncement.LAYER_FULL
                            : FarViewAnnouncement.LAYER_COARSE, pack.length, digest,
                            urlRoot + "/" + MAP_ID + "/farview/" + hex + ".hfp"));
                }
            }
            final MsgBuf buf = MsgBuf.writing();
            new FarViewAnnouncement(buildId, Synthetic.BASE_LEVEL, FarViewPack.COARSE_LEVEL, MIN_Y, HEIGHT, tiles).write(buf);
            return new Content(MAP_ID, served, buf.toByteArray(), packs, Synthetic.views());
        }

        /** The objects of the packs in the release that the shard announced, as the CDN would hold them. */
        static Content baked(Path buildDir, SynxManifest manifest, byte @Nullable [] announcement) {
            final Map<String, byte[]> served = new TreeMap<>();
            final Map<String, byte[]> packs = new TreeMap<>();
            if (announcement != null) {
                for (FarViewAnnouncement.Tile tile : FarViewAnnouncement.read(MsgBuf.reading(announcement)).tiles()) {
                    final byte[] whole;
                    try {
                        whole = Files.readAllBytes(buildDir.resolve("farview").resolve("t." + tile.tileX() + "." + tile.tileZ()
                                + ".hfp"));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    final byte[] object = Arrays.copyOf(whole, (int) tile.bytes());
                    served.put(SynxDigests.hex(tile.sha256()), object);
                    packs.put(tile.tileX() + " " + tile.tileZ(), object);
                }
            }
            return new Content(manifest.mapId(), served, announcement, packs, Synthetic.views());
        }

        String describe() {
            return packs.size() + " tile(s) " + served.keySet().stream().map(hex -> hex.substring(0, 12)).toList();
        }
    }

    /**
     * The packs of {@code synthetic}, all from one function of the column: terraces stepping 4 blocks
     * every 128 blocks across, a stepped hill of ten terraces up to y 150 around (1500, 900), a lake of
     * water 3 deep over sand at x 700..955, z 300..555, a red concrete pillar 14 high on every column
     * whose x and z are multiples of 64 (where the world is high enough), and in tile (-1, 0) a mountain up to y 185 around (-600, 300).
     * The hidden area is blocks 1200..1263 on both axes, chunk aligned: under it is a red concrete
     * tower up to y 180, which the hidden rule of {@code API-M3-014} replaces on both layers. A fine
     * sample is the column at its north west corner, which is where the pillars stand. Base level 2.
     */
    static final class Synthetic {
        static final int BASE_LEVEL = 2;
        static final List<String> BIOMES = List.of("minecraft:plains", "minecraft:river", "minecraft:windswept_hills");
        static final int PLAINS = 0, RIVER = 1, HILLS = 2;
        static final int HIDDEN_MIN = 1200, HIDDEN_MAX = 1263;
        /** How far a pillar stands above the ground, where the top of the world leaves room for it. */
        static final int PILLAR = 14;
        /** The columns of the four points of {@code /expect.json}: x, z. */
        static final int[] PEAK = {1500, 900}, WATER = {800, 400}, HIDDEN = {1232, 1232}, MOUNTAIN = {-600, 300};

        private static final int SAMPLE = 1 << BASE_LEVEL;
        private static final int FINE_SIDE = FarViewPack.TILE_BLOCKS / SAMPLE;

        private Synthetic() {
        }

        private static int index(int y) {
            return y - MIN_Y;
        }

        static boolean inLake(int x, int z) {
            return x >= 700 && x <= 955 && z >= 300 && z <= 555;
        }

        static boolean inHidden(int x, int z) {
            return x >= HIDDEN_MIN && x <= HIDDEN_MAX && z >= HIDDEN_MIN && z <= HIDDEN_MAX;
        }

        /** The absolute y of the top of the ground of a column, before the pillars. */
        static int ground(int x, int z) {
            final int terraces = 64 + 4 * (((x >> 7) + (z >> 7)) & 7);
            final int hillStep = Math.max(Math.abs(x - PEAK[0]), Math.abs(z - PEAK[1])) / 32;
            final int hill = hillStep < 10 ? 150 - 6 * hillStep : Integer.MIN_VALUE;
            final double fromMountain = Math.hypot(x - MOUNTAIN[0], z - MOUNTAIN[1]);
            final int mountain = fromMountain < 600 ? 185 - (int) (fromMountain / 5) : Integer.MIN_VALUE;
            return Math.max(terraces, Math.max(hill, mountain));
        }

        /** The column as a sample, the hidden tower still standing. */
        static Sample column(int x, int z) {
            if (inLake(x, z)) {
                return new Sample(Block.SAND.stateId(), Block.WATER.stateId(), index(57), index(58), index(60), RIVER);
            }
            if (inHidden(x, z)) return new Sample(Block.RED_CONCRETE.stateId(), 0, index(180), 0, 0, PLAINS);
            final int ground = ground(x, z);
            final int biome = ground > 100 ? HILLS : PLAINS;
            if (Math.floorMod(x, 64) == 0 && Math.floorMod(z, 64) == 0 && ground + PILLAR < MIN_Y + HEIGHT) {
                return new Sample(Block.RED_CONCRETE.stateId(), 0, index(ground + PILLAR), 0, 0, biome);
            }
            return new Sample(ground > 120 ? Block.STONE.stateId() : Block.GRASS_BLOCK.stateId(), 0, index(ground), 0, 0, biome);
        }

        /** The fine samples of a tile before the hidden area is replaced, {@code [sz][sx]}. */
        static Sample[][] fine(int tileX, int tileZ) {
            final Sample[][] fine = new Sample[FINE_SIDE][FINE_SIDE];
            for (int sz = 0; sz < FINE_SIDE; sz++) {
                for (int sx = 0; sx < FINE_SIDE; sx++) {
                    fine[sz][sx] = column(tileX * FarViewPack.TILE_BLOCKS + sx * SAMPLE, tileZ * FarViewPack.TILE_BLOCKS + sz * SAMPLE);
                }
            }
            return fine;
        }

        /** The coarse samples of a tile from its fine samples before the hidden area, {@code [cz][cx]}. */
        static Sample[][] coarse(Sample[][] fine) {
            final int perChunk = 16 / SAMPLE, side = FINE_SIDE / perChunk;
            final Sample[][] coarse = new Sample[side][side];
            for (int cz = 0; cz < side; cz++) {
                for (int cx = 0; cx < side; cx++) {
                    final List<Sample> chunk = new ArrayList<>(perChunk * perChunk);
                    for (int z = 0; z < perChunk; z++) {
                        for (int x = 0; x < perChunk; x++) chunk.add(fine[cz * perChunk + z][cx * perChunk + x]);
                    }
                    coarse[cz][cx] = FarViewPack.coarsen(BASE_LEVEL, chunk);
                }
            }
            return coarse;
        }

        /**
         * The hidden rule of {@code API-M3-014} on one grid of tile (0, 0), the only tile it meets: every
         * sample of {@code [min, max]} becomes stone of plains at the lowest top of the samples around
         * them. The area is inside the tile on both grids, so its rim is too.
         */
        static void hide(Sample[][] grid, int min, int max) {
            int rim = Integer.MAX_VALUE;
            for (int z = min - 1; z <= max + 1; z++) {
                for (int x = min - 1; x <= max + 1; x++) {
                    if (x < min || x > max || z < min || z > max) rim = Math.min(rim, Math.max(0, grid[z][x].top()));
                }
            }
            for (int z = min; z <= max; z++) {
                for (int x = min; x <= max; x++) grid[z][x] = new Sample(Block.STONE.stateId(), 0, rim, 0, 0, PLAINS);
            }
        }

        static byte[] wholePack() {
            final Sample[][] fine = fine(0, 0);
            final Sample[][] coarse = coarse(fine);
            hide(fine, HIDDEN_MIN / SAMPLE, HIDDEN_MAX / SAMPLE);
            hide(coarse, HIDDEN_MIN >> 4, HIDDEN_MAX >> 4);
            final FarViewPack.Writer writer = writer(0, 0);
            addBlocks(writer, Layer.COARSE, coarse);
            addBlocks(writer, Layer.FINE, fine);
            return writer.write();
        }

        static byte[] coarsePack(int tileX, int tileZ) {
            final FarViewPack.Writer writer = writer(tileX, tileZ);
            addBlocks(writer, Layer.COARSE, coarse(fine(tileX, tileZ)));
            return writer.coarsePrefix();
        }

        private static FarViewPack.Writer writer(int tileX, int tileZ) {
            return new FarViewPack.Writer(BASE_LEVEL, tileX, tileZ, MIN_Y, HEIGHT, Block.statesCount(), 776, BIOMES);
        }

        private static void addBlocks(FarViewPack.Writer writer, Layer layer, Sample[][] grid) {
            final int blocks = grid.length / FarViewPack.BLOCK_SIDE;
            for (int bz = 0; bz < blocks; bz++) {
                for (int bx = 0; bx < blocks; bx++) {
                    final List<Sample> block = new ArrayList<>(FarViewPack.BLOCK_SAMPLES);
                    for (int z = 0; z < FarViewPack.BLOCK_SIDE; z++) {
                        for (int x = 0; x < FarViewPack.BLOCK_SIDE; x++) {
                            block.add(grid[bz * FarViewPack.BLOCK_SIDE + z][bx * FarViewPack.BLOCK_SIDE + x]);
                        }
                    }
                    writer.block(layer, bx, bz, block);
                }
            }
        }

        /**
         * The camera spots, {@code x, y, z, yaw, pitch} (yaw 90 faces west, -90 east; a positive pitch
         * looks down): EDGE_VIEW is set from the spawn point by {@link #expectJson}; the four of V3b are a
         * plain, the lake, the hill's ridge and 100 blocks inside the west edge of tile (0, 0) looking out.
         */
        static Map<String, double[]> views() {
            final Map<String, double[]> views = new TreeMap<>();
            views.put("plain", new double[]{2600.5, 130, 2600.5, -90, 10});
            views.put("lake", new double[]{690.5, 100, 430.5, -90, 20});
            views.put("ridge", new double[]{1100.5, 170, 900.5, -90, 5});
            views.put("edge-outward", new double[]{100.5, 140, 300.5, 90, 10});
            return views;
        }
    }

    /**
     * {@code /expect.json}: the four points of V1 ④ with the block and the absolute y of the top of
     * their column as the packs hold it (the whole pack's fine sample in tile (0, 0), the coarse sample
     * of the tile around), and the camera spots. In {@code baked} the points are the highest fine sample
     * of tile (0, 0), its first sample with fluid and the highest coarse sample of tile (-1, 0); a
     * generator source hides nothing, so there is no hidden point.
     */
    static String expectJson(String mode, Content content, Pos spawn) {
        final JsonObject json = new JsonObject();
        json.addProperty("mode", mode);
        json.addProperty("map_id", content.mapId());
        final JsonArray points = new JsonArray();
        final byte[] whole = content.packs().get("0 0");
        final byte[] west = content.packs().get("-1 0");
        if (mode.equals("baked")) {
            if (whole != null) {
                pick(whole, Layer.FINE, Sample::top).ifPresent(at -> points.add(point("peak", whole, Layer.FINE, at)));
                pick(whole, Layer.FINE, s -> s.hasFluid() ? 1 : -1).ifPresent(at -> points.add(point("water", whole, Layer.FINE, at)));
            }
            if (west != null) {
                pick(west, Layer.COARSE, Sample::top).ifPresent(at -> points.add(point("mountain", west, Layer.COARSE, at)));
            }
        } else {
            points.add(point("peak", Objects.requireNonNull(whole), Layer.FINE, Synthetic.PEAK));
            points.add(point("water", whole, Layer.FINE, Synthetic.WATER));
            points.add(point("hidden", whole, Layer.FINE, Synthetic.HIDDEN));
            points.add(point("mountain", Objects.requireNonNull(west), Layer.COARSE, Synthetic.MOUNTAIN));
        }
        json.add("points", points);
        final JsonObject views = new JsonObject();
        views.add("EDGE_VIEW", view(new double[]{spawn.x(), spawn.y() + 100, spawn.z(), 90, 15}));
        content.views().forEach((name, view) -> views.add(name, view(view)));
        json.add("views", views);
        json.addProperty("spawn", spawn.x() + " " + spawn.y() + " " + spawn.z());
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json) + "\n";
    }

    private static JsonObject view(double[] view) {
        final JsonObject json = new JsonObject();
        json.addProperty("x", view[0]);
        json.addProperty("y", view[1]);
        json.addProperty("z", view[2]);
        json.addProperty("yaw", view[3]);
        json.addProperty("pitch", view[4]);
        return json;
    }

    /** The sample of a pack's layer covering the column {@code x, z}. */
    static Sample sampleAt(byte[] pack, Layer layer, int x, int z) {
        try {
            final FarViewPack.Reader reader = FarViewPack.Reader.read(pack);
            final int level = layer == Layer.COARSE ? FarViewPack.COARSE_LEVEL : reader.header().baseLevel();
            final int sx = (x - reader.header().tileX() * FarViewPack.TILE_BLOCKS) >> level;
            final int sz = (z - reader.header().tileZ() * FarViewPack.TILE_BLOCKS) >> level;
            for (FarViewPack.Block block : reader.blocks(layer)) {
                if (block.bx() == sx / FarViewPack.BLOCK_SIDE && block.bz() == sz / FarViewPack.BLOCK_SIDE) {
                    return reader.samples(block).get(sz % FarViewPack.BLOCK_SIDE * FarViewPack.BLOCK_SIDE + sx % FarViewPack.BLOCK_SIDE);
                }
            }
            return Sample.EMPTY;
        } catch (FarViewPack.Invalid e) {
            throw new IllegalStateException(e);
        }
    }

    /** The column (north west corner of its sample) of the sample with the largest score, first by z and then x. */
    private static Optional<int[]> pick(byte[] pack, Layer layer, ToIntFunction<Sample> score) {
        try {
            final FarViewPack.Reader reader = FarViewPack.Reader.read(pack);
            final int level = layer == Layer.COARSE ? FarViewPack.COARSE_LEVEL : reader.header().baseLevel();
            int best = 0;
            int[] at = null;
            for (FarViewPack.Block block : reader.blocks(layer)) {
                final List<Sample> samples = reader.samples(block);
                for (int i = 0; i < samples.size(); i++) {
                    final int value = score.applyAsInt(samples.get(i));
                    if (value < 0) continue;
                    if (at != null && value <= best) continue;
                    best = value;
                    at = new int[]{reader.header().tileX() * FarViewPack.TILE_BLOCKS
                            + ((block.bx() * FarViewPack.BLOCK_SIDE + i % FarViewPack.BLOCK_SIDE) << level),
                            reader.header().tileZ() * FarViewPack.TILE_BLOCKS
                                    + ((block.bz() * FarViewPack.BLOCK_SIDE + i / FarViewPack.BLOCK_SIDE) << level)};
                }
            }
            return Optional.ofNullable(at);
        } catch (FarViewPack.Invalid e) {
            throw new IllegalStateException(e);
        }
    }

    /** One point: where, which layer, and the top block of the column with its absolute y. */
    private static JsonObject point(String name, byte[] pack, Layer layer, int[] at) {
        final Sample sample = sampleAt(pack, layer, at[0], at[1]);
        final boolean fluid = sample.hasFluid();
        final int state = fluid ? sample.fluidState() : sample.groundState();
        final Block block = Block.fromStateId(state);
        final JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("x", at[0]);
        json.addProperty("z", at[1]);
        json.addProperty("tile_x", Math.floorDiv(at[0], FarViewPack.TILE_BLOCKS));
        json.addProperty("tile_z", Math.floorDiv(at[1], FarViewPack.TILE_BLOCKS));
        json.addProperty("layer", layer == Layer.COARSE ? "coarse" : "fine");
        json.addProperty("block", block == null ? "unknown" : block.key().asString());
        json.addProperty("state", state);
        json.addProperty("y", (fluid ? sample.fluidTop() : sample.groundY()) + MIN_Y);
        return json;
    }

    /**
     * The HTTP side: the objects by their digest, {@code Range} answered with 206, one shared bucket of
     * {@value #BYTES_PER_SECOND} B/s paid before each piece is written, and the request log.
     */
    static final class PackServer {
        private static final int PIECE = 16 * 1024;

        private final String mapId;
        private final Map<String, byte[]> objects;
        private final boolean half;
        private final byte[] expect;
        private final List<String> log = new CopyOnWriteArrayList<>();
        private final AtomicBoolean cut = new AtomicBoolean();
        private final Object bucket = new Object();
        private long nextFree = System.nanoTime();
        private @Nullable HttpServer server;

        PackServer(String mapId, Map<String, byte[]> objects, boolean half, String expect) {
            this.mapId = mapId;
            this.objects = Map.copyOf(objects);
            this.half = half;
            this.expect = expect.getBytes(StandardCharsets.UTF_8);
        }

        void start(int port) throws IOException {
            final HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            server.setExecutor(Executors.newCachedThreadPool(Thread.ofPlatform().daemon().name("farview-http-", 0).factory()));
            server.createContext("/", this::handle);
            server.start();
            this.server = server;
        }

        int port() {
            return Objects.requireNonNull(server, "not started").getAddress().getPort();
        }

        void stop() {
            if (server != null) server.stop(0);
        }

        List<String> log() {
            return List.copyOf(log);
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                final String path = exchange.getRequestURI().getPath();
                if (path.equals("/expect.json")) {
                    send(exchange, 200, "application/json", expect);
                    return;
                }
                if (path.equals("/access.log")) {
                    send(exchange, 200, "text/plain; charset=utf-8", (String.join("\n", log) + (log.isEmpty() ? "" : "\n"))
                            .getBytes(StandardCharsets.UTF_8));
                    return;
                }
                final String range = exchange.getRequestHeaders().getFirst("Range");
                final String method = exchange.getRequestMethod();
                final Matcher pack = PACK_PATH.matcher(path);
                final byte[] object = pack.matches() && pack.group(1).equals(mapId) ? objects.get(pack.group(2)) : null;
                if (object == null || !method.equals("GET")) {
                    final int status = object == null ? 404 : 405;
                    log.add(method + " " + path + " " + (range == null ? "-" : range) + " " + status);
                    exchange.sendResponseHeaders(status, -1);
                    return;
                }
                int from = 0, to = object.length - 1, status = 200;
                final Matcher bytes = range == null ? null : RANGE.matcher(range);
                if (bytes != null && bytes.matches()) {
                    from = Integer.parseInt(bytes.group(1));
                    if (!bytes.group(2).isEmpty()) to = Math.min(to, Integer.parseInt(bytes.group(2)));
                    if (from >= object.length || from > to) {
                        log.add(method + " " + path + " " + range + " 416");
                        exchange.getResponseHeaders().set("Content-Range", "bytes */" + object.length);
                        exchange.sendResponseHeaders(416, -1);
                        return;
                    }
                    status = 206;
                    exchange.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + object.length);
                }
                final int length = to - from + 1;
                final boolean cutShort = half && cut.compareAndSet(false, true);
                log.add(method + " " + path + " " + (range == null ? "-" : range) + " " + status + (cutShort ? " cut" : ""));
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                exchange.sendResponseHeaders(status, length);
                final OutputStream body = exchange.getResponseBody();
                final int end = cutShort ? from + length / 2 : from + length;
                for (int at = from; at < end; at += PIECE) {
                    final int piece = Math.min(PIECE, end - at);
                    pay(piece);
                    body.write(object, at, piece);
                    body.flush();
                }
                // A handler that throws makes the server drop the connection, short of the length it announced
                if (cutShort) throw new IOException("cut half way, as the mode half does");
            }
        }

        private static void send(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        /** Waits until {@code bytes} are paid for out of the shared bucket, which lends nothing ahead. */
        private void pay(int bytes) {
            final long due;
            synchronized (bucket) {
                final long start = Math.max(System.nanoTime(), nextFree);
                nextFree = start + bytes * TimeUnit.SECONDS.toNanos(1) / BYTES_PER_SECOND;
                due = nextFree;
            }
            final long wait = due - System.nanoTime();
            if (wait > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(wait);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
