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
import io.github.bearisbug.synaxis.baker.BakeSource;
import io.github.bearisbug.synaxis.baker.Baker;
import io.github.bearisbug.synaxis.baker.GeneratorPreset;
import io.github.bearisbug.synaxis.baker.GeneratorSource;
import io.github.bearisbug.synaxis.baker.LightVersions;
import io.github.bearisbug.synaxis.baker.SyntheticTerrain;
import io.github.bearisbug.synaxis.baker.SourceChunk;
import io.github.bearisbug.synaxis.baker.anvil.HiddenArea;
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
import io.github.bearisbug.synaxis.instance.Clock;
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
import io.github.bearisbug.synaxis.world.DimensionType;
import io.github.bearisbug.synaxis.world.biome.Biome;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
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
 * {@code main(port, httpPort, workDir, mode[, urlRoot[, dayTime[, rttMillis]]])}: the game on
 * {@code 0.0.0.0:<port>}, the packs on {@code 0.0.0.0:<httpPort>} under {@code /<map_id>/farview/<sha256>.hfp},
 * every connection sharing one bucket of {@value #BYTES_PER_SECOND} B/s (50 Mbit/s). {@code urlRoot} is the
 * root of the announced URLs, {@code http://host.docker.internal:<httpPort>} unless given ({@code -} keeps that
 * default): what a client in the container sees. {@code dayTime} (ticks, -1 lets the day run) stops the
 * overworld clock of the map at that time, so the sun stands still (V6b); the map's dimension type then takes
 * the overworld's timelines and clock, without which the 26.2 client keeps its sun at noon. {@code rttMillis}
 * (0 for none) puts a TCP proxy in front of the game port that holds every byte for half of it each way (P1③).
 * {@code /expect.json} gives the points to check in the library and the camera spots, {@code /access.log} one
 * line per pack request: method, path, {@code Range} header ({@code -} without), status, and {@code cut} for a
 * response cut short; {@code /rearm} lets {@code half} cut its next response again. Prints {@value #READY}
 * once both listen.
 * <p>
 * The system property {@value #CLOCK_RUNS} set to {@code true} sets the time without stopping the clock: the
 * counter-proof of the stopped sun ({@code docs/DESIGN-M3.md} §19 「夹具定住日间时间」).
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
 * <li>{@code baked-hidden}: as {@code baked}, map {@value #HIDDEN_MAP_ID} baked apart from it, with the source
 * hiding {@link #HIDDEN_AREAS}: one over the highest fine sample of tile (0, 0) in {@code baked}, one across the
 * edge of tiles (-1, 0) and (0, 0), so the coarse layer has a replacement too. {@code /expect.json} adds three
 * hidden points (fine over the peak, fine and coarse across the edge) at what the hidden rule of
 * {@code API-M3-014} makes of them: stone of plains at the lowest top of the ring of their area.</li>
 * <li>{@code counter-hidden}: {@code baked}'s release, whose packs hide nothing, with the {@code /expect.json} of
 * {@code baked-hidden}: the three hidden points read back the terrain, the counter-proof.</li>
 * <li>{@code serve-only}: no game port. {@code baked}'s release exported as {@code farview-export} lays it out
 * (every tile's whole pack and coarse prefix under {@code /<map_id>/farview/<sha256>.hfp}) for a
 * {@code bin/server-shard} of the same release, in the memory store, whose {@code synaxis.farview.url-root} is
 * this server: its bounds are the manifest's, so it announces the nine whole packs. {@code /expect.json} takes
 * the spawn of those bounds and its points from the whole packs.</li>
 * </ul>
 */
final class FarViewShardFixture {
    static final String READY = "farview fixture ready";
    /** The map of {@code synthetic}, {@code break-sha} and {@code half}. */
    static final String MAP_ID = "farview";
    /** The map of {@code baked}, {@code counter-hidden} and {@code serve-only}. */
    static final String BAKED_MAP_ID = "farview3x3";
    /** The map of {@code baked-hidden}. */
    static final String HIDDEN_MAP_ID = "farview3x3h";
    static final List<String> MODES = List.of("synthetic", "baked", "break-sha", "half", "baked-hidden", "counter-hidden",
            "serve-only");
    /** The modes on the baker's 3 × 3 tiles. */
    static final Set<String> BAKED_MODES = Set.of("baked", "baked-hidden", "counter-hidden", "serve-only");
    /** Set to {@code true}: the time is set and the clock left running, the counter-proof of {@code dayTime}. */
    static final String CLOCK_RUNS = "farview.fixture.clock-runs";
    /**
     * The hidden areas of {@code baked-hidden}, by x1: across the edge of tiles (-1, 0) and (0, 0) on the spawn's
     * z, and over the highest fine sample of tile (0, 0) in {@code baked}, column 440, 104.
     */
    static final List<HiddenArea> HIDDEN_AREAS = List.of(new HiddenArea(-64, 1984, 63, 2111), new HiddenArea(400, 64, 479, 143));
    /** The hidden points of {@code /expect.json}: over the peak, and either side of the tile edge. */
    static final List<HiddenPoint> HIDDEN_POINTS = List.of(new HiddenPoint("hidden-peak", Layer.FINE, 440, 104),
            new HiddenPoint("hidden-edge-fine", Layer.FINE, 32, 2048), new HiddenPoint("hidden-edge-coarse", Layer.COARSE, -32, 2048));

    /** A point of {@code /expect.json} a hidden area covers: the north west corner of a sample of {@code layer}. */
    record HiddenPoint(String name, Layer layer, int x, int z) {
    }
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
    public static void main(String[] args) throws IOException, InterruptedException {
        final int port = Integer.parseInt(args[0]);
        final int httpPort = Integer.parseInt(args[1]);
        final Path workDir = Path.of(args[2]);
        final String mode = args[3];
        final String urlRoot = args.length > 4 && !args[4].equals("-") ? args[4] : "http://host.docker.internal:" + httpPort;
        final long dayTime = args.length > 5 ? Long.parseLong(args[5]) : -1;
        final int rttMillis = args.length > 6 ? Integer.parseInt(args[6]) : 0;
        if (!MODES.contains(mode)) throw new IllegalArgumentException("mode is one of " + MODES + ", not " + mode);
        // What ShardMain writes, where the command line said nothing
        final int cores = Runtime.getRuntime().availableProcessors();
        setIfAbsent("synaxis.thread-provider", "grid");
        setIfAbsent("synaxis.dispatcher-threads", Integer.toString(Math.max(1, cores - 2)));
        setIfAbsent("synaxis.chunk-view-distance", "12");
        setIfAbsent("synaxis.chunk-queue.max-per-tick", "4");
        setIfAbsent("synaxis.max-visible-entities-per-player", "100");

        final boolean baked = BAKED_MODES.contains(mode);
        final Path mapsRoot = workDir.resolve("maps");
        final ShardSettings release = baked
                ? release(mapsRoot, mode.equals("baked-hidden") ? HIDDEN_MAP_ID : BAKED_MAP_ID, bakedSource(mode.equals("baked-hidden")),
                Math.min(cores, 3))
                : release(mapsRoot, MAP_ID, new GeneratorSource(new GeneratorPreset(FarViewShardFixture::generateBelowTop, MIN_Y, HEIGHT,
                LightMapFixture.BOUNDS), Registries.vanilla()), Math.max(1, cores / 2));
        if (mode.equals("serve-only")) {
            serveOnly(release, httpPort, dayTime);
            return;
        }
        final ShardSettings settings = new ShardSettings(release.mapRoot(), release.mapBuild(), 4, 100,
                baked ? urlRoot : "");
        final ShardDirectory directory = baked ? new ShardDirectory(release.mapRoot().getFileName().toString(),
                List.of(new ShardDirectory.Region("r0", BAKED_REGION, List.of())),
                List.of(new ShardDirectory.Channel("r0-c1", "r0", 1, "127.0.0.1", port, 500)), "r0-c1") : null;

        final MinecraftServer server = MinecraftServer.init();
        final ServerProcess process = server.process();
        final ShardWorld world = ShardWorld.prepare(process, settings, SynxOptions.fromSystemProperties(),
                OverlayOptions.fromSystemProperties(), directory);
        if (dayTime >= 0) withOverworldClock(process);
        final ShutdownSequence shutdown = new ShutdownSequence(process, new NoPlayers());
        process.setShutdownParticipant(shutdown);
        final ShardWorld.Started started = world.start(shutdown, (_, _) -> {
        }, null, workDir.resolve("overlay"));
        final InstanceContainer instance = started.instance();
        final Pos spawn = started.spawn();
        if (dayTime >= 0) holdTime(instance, dayTime, Boolean.getBoolean(CLOCK_RUNS));
        final SynxManifest manifest = manifest(settings.buildDir());

        final Content content = baked ? Content.baked(settings.buildDir(), manifest.mapId(), started.farView())
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
                expectJson(mode, content, spawn, dayTime));
        packs.start(httpPort);
        if (rttMillis > 0) {
            final int gamePort = freePort();
            server.start("127.0.0.1", gamePort);
            new DelayProxy(port, gamePort, rttMillis).start();
            System.out.println("farview fixture: the game behind a proxy on 0.0.0.0:" + port + " to 127.0.0.1:" + gamePort
                    + ", " + rttMillis + " ms round trip");
        } else {
            server.start("0.0.0.0", port);
        }
        if (announcement != null) {
            for (FarViewAnnouncement.Tile tile : FarViewAnnouncement.read(MsgBuf.reading(announcement)).tiles()) {
                System.out.println("farview fixture: tile " + tile.tileX() + " " + tile.tileZ() + " layer " + tile.layer()
                        + " bytes " + tile.bytes() + " sha256 " + SynxDigests.hex(tile.sha256()) + " url " + tile.url());
            }
        }
        System.out.println("farview fixture: mode=" + mode + " build_id=" + manifest.buildId() + " objects="
                + content.served().size() + " announced=" + (announcement == null ? "none" : content.describe())
                + " http=0.0.0.0:" + packs.port() + (dayTime >= 0 ? " day_time=" + dayTime
                + (Boolean.getBoolean(CLOCK_RUNS) ? " (clock running)" : " (clock stopped)") : ""));
        System.out.println(READY);
    }

    /**
     * {@code serve-only}: the release's packs as {@code farview-export} lays them out, and {@code /expect.json} for
     * a {@code bin/server-shard} of it in the memory store, nothing else; runs until killed.
     */
    private static void serveOnly(ShardSettings release, int httpPort, long dayTime) throws IOException, InterruptedException {
        final SynxManifest manifest = manifest(release.buildDir());
        final Content content = Content.exported(release.buildDir(), manifest);
        final Pos spawn = ShardWorld.spawn(release.buildDir(), manifest, manifest.bounds());
        final PackServer packs = new PackServer(content.mapId(), content.served(), false,
                expectJson("serve-only", content, spawn, dayTime));
        packs.start(httpPort);
        for (String object : content.served().keySet()) {
            System.out.println("farview fixture: object /" + content.mapId() + "/farview/" + object + ".hfp bytes "
                    + content.served().get(object).length);
        }
        System.out.println("farview fixture: mode=serve-only build_id=" + manifest.buildId() + " map_root=" + release.mapRoot()
                + " objects=" + content.served().size() + " spawn=" + spawn + " http=0.0.0.0:" + packs.port());
        System.out.println(READY);
        new CountDownLatch(1).await();
    }

    /** The source of the 3 × 3 tiles, hiding {@link #HIDDEN_AREAS} for {@code baked-hidden}. */
    static BakeSource bakedSource(boolean hidden) {
        final GeneratorSource source = new GeneratorSource(new GeneratorPreset(new SyntheticTerrain(0), 0, SyntheticTerrain.HEIGHT,
                BAKED_BOUNDS), Registries.vanilla());
        return hidden ? new Hiding(source, HIDDEN_AREAS) : source;
    }

    /** A generator source with the hidden areas an Anvil source collects from its {@code synaxis:hidden} markers. */
    private record Hiding(GeneratorSource source, List<HiddenArea> hidden) implements BakeSource {
        @Override
        public String kind() {
            return source.kind();
        }

        @Override
        public int minY() {
            return source.minY();
        }

        @Override
        public int height() {
            return source.height();
        }

        @Override
        public SynxManifest.Bounds bounds() {
            return source.bounds();
        }

        @Override
        public @Nullable Integer dataVersion() {
            return null;
        }

        @Override
        public @Nullable Double lightCoverage() {
            return null;
        }

        @Override
        public void checkGate(int threads) {
        }

        @Override
        public List<HiddenArea> hiddenAreas() {
            return hidden;
        }

        @Override
        public SourceChunk read(int chunkX, int chunkZ) {
            return source.read(chunkX, chunkZ);
        }

        @Override
        public void close() {
        }
    }

    /**
     * Gives the map's dimension type the overworld's timelines and clock. {@link ShardWorld} registers it without
     * them, and a 26.2 client draws the sun of a dimension type without the {@code minecraft:day} timeline at the
     * default angle 0, noon, whatever the time; with them the sun follows the overworld clock.
     */
    static void withOverworldClock(ServerProcess process) {
        final Key key = Key.key(ShardWorld.DIMENSION_TYPE);
        final DimensionType map = Objects.requireNonNull(process.dimensionType().get(key), ShardWorld.DIMENSION_TYPE);
        final DimensionType overworld = Objects.requireNonNull(process.dimensionType().get(DimensionType.OVERWORLD));
        process.dimensionType().register(key, DimensionType.builder(map).timelines(overworld.timelines())
                .defaultClock(Objects.requireNonNull(overworld.defaultClock(), "the overworld has a clock")).build());
    }

    /** Sets the default clock to {@code dayTime} and, unless {@code running}, stops it there. */
    static void holdTime(InstanceContainer instance, long dayTime, boolean running) {
        final Clock clock = Objects.requireNonNull(instance.defaultClock(), "the map has a clock");
        clock.time(dayTime);
        if (!running) clock.pause();
    }

    private static SynxManifest manifest(Path buildDir) throws IOException {
        try {
            return SynxManifest.fromJson(Files.readString(buildDir.resolve(SynxManifest.FILE_NAME)));
        } catch (SynxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /**
     * A TCP proxy that holds every chunk of bytes {@code rttMillis / 2} in each direction before passing it on:
     * the round trip of P1③ ({@code docs/DESIGN-M3.md} §26 M3-S, 真机六项), the bandwidth untouched.
     */
    static final class DelayProxy {
        private final int port;
        private final int target;
        private final long delayNanos;
        private @Nullable ServerSocket socket;

        DelayProxy(int port, int target, int rttMillis) {
            this.port = port;
            this.target = target;
            this.delayNanos = TimeUnit.MILLISECONDS.toNanos(rttMillis) / 2;
        }

        int port() {
            return Objects.requireNonNull(socket, "not started").getLocalPort();
        }

        void start() throws IOException {
            final ServerSocket listening = new ServerSocket();
            listening.bind(new InetSocketAddress("0.0.0.0", port));
            socket = listening;
            Thread.ofPlatform().daemon().name("farview-proxy-accept").start(() -> {
                while (!listening.isClosed()) {
                    try {
                        final Socket client = listening.accept();
                        final Socket server = new Socket(InetAddress.getLoopbackAddress(), target);
                        client.setTcpNoDelay(true);
                        server.setTcpNoDelay(true);
                        pipe(client, server);
                        pipe(server, client);
                    } catch (IOException _) {
                        // a closed listener ends the loop; a failed connection only that connection
                    }
                }
            });
        }

        void stop() throws IOException {
            if (socket != null) socket.close();
        }

        /** Bytes read from one side, due on the other at {@code due} ({@link System#nanoTime()}); null at the end. */
        private record Piece(long due, byte @Nullable [] bytes) {
        }

        /** One direction: a reader stamps each chunk with the time it is due, a writer sends it then. */
        private void pipe(Socket from, Socket to) {
            final LinkedBlockingQueue<Piece> due = new LinkedBlockingQueue<>();
            Thread.ofPlatform().daemon().name("farview-proxy-read").start(() -> {
                final byte[] buffer = new byte[64 * 1024];
                try {
                    final InputStream in = from.getInputStream();
                    for (int read; (read = in.read(buffer)) >= 0; ) {
                        due.put(new Piece(System.nanoTime() + delayNanos, Arrays.copyOf(buffer, read)));
                    }
                } catch (IOException | InterruptedException _) {
                    // the connection ended
                }
                due.add(new Piece(System.nanoTime() + delayNanos, null));
            });
            Thread.ofPlatform().daemon().name("farview-proxy-write").start(() -> {
                try (from; to) {
                    final OutputStream out = to.getOutputStream();
                    while (true) {
                        final Piece next = due.take();
                        final long wait = next.due() - System.nanoTime();
                        if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
                        if (next.bytes() == null) break;
                        out.write(next.bytes());
                        out.flush();
                    }
                } catch (IOException | InterruptedException _) {
                    // the connection ended
                }
            });
        }
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
    static ShardSettings release(Path mapsRoot, String mapId, BakeSource source, int threads) {
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
        final Baker.Result result = Baker.bake(new Baker.Request(mapId, source,
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
     * @param full         the whole packs of the release on disk by the same key, both layers of every tile,
     *                     what the hidden rule reads its rings from
     */
    record Content(String mapId, Map<String, byte[]> served, byte @Nullable [] announcement,
                   Map<String, byte[]> packs, Map<String, byte[]> full) {

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
            return new Content(MAP_ID, served, buf.toByteArray(), packs, packs);
        }

        /** The objects of the packs in the release that the shard announced, as the CDN would hold them. */
        static Content baked(Path buildDir, String mapId, byte @Nullable [] announcement) {
            final Map<String, byte[]> served = new TreeMap<>();
            final Map<String, byte[]> packs = new TreeMap<>();
            final Map<String, byte[]> full = new TreeMap<>();
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
                    full.put(tile.tileX() + " " + tile.tileZ(), whole);
                }
            }
            return new Content(mapId, served, announcement, packs, full);
        }

        /**
         * {@code serve-only}: every pack of the release's {@code farview[]} as {@code farview-export} writes it, the
         * whole pack and its coarse prefix under their digests; a shard of the manifest's bounds announces them whole.
         */
        static Content exported(Path buildDir, SynxManifest manifest) throws IOException {
            final Map<String, byte[]> served = new TreeMap<>();
            final Map<String, byte[]> packs = new TreeMap<>();
            for (SynxManifest.FarView entry : manifest.farview()) {
                final byte[] whole = Files.readAllBytes(buildDir.resolve("farview").resolve("t." + entry.tileX() + "."
                        + entry.tileZ() + ".hfp"));
                final byte[] prefix = Arrays.copyOf(whole, (int) entry.coarseBytes());
                if (!Arrays.equals(SynxDigests.sha256(whole), entry.sha256()) || !Arrays.equals(SynxDigests.sha256(prefix),
                        entry.coarseSha256())) {
                    throw new IllegalStateException("farview/t." + entry.tileX() + "." + entry.tileZ() + ".hfp is not the manifest's");
                }
                served.put(SynxDigests.hex(entry.sha256()), whole);
                served.put(SynxDigests.hex(entry.coarseSha256()), prefix);
                packs.put(entry.tileX() + " " + entry.tileZ(), whole);
            }
            return new Content(manifest.mapId(), served, null, packs, packs);
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
     * The camera spots of {@code baked}, found in its packs: the baker's terrain is not {@link Synthetic}'s
     * function, and its spawn, the center of tile (0, 0), is 2,040 blocks from the west edge. The same
     * five as synthetic, each {@code x, y, z, yaw, pitch}, standing above what the packs draw there:
     * <ul>
     * <li>{@code EDGE_VIEW}: {@value #EDGE_INSET} blocks inside the west edge of tile (0, 0) (where synthetic's
     * spawn is) on the spawn's z, 100 above the highest column from {@value #EDGE_PAST} blocks past the edge
     * back to it, facing west 15° down. The line of sight comes down 100 blocks in 373, so nothing on the way
     * stops it before it is {@value #EDGE_PAST} blocks into tile (-1, 0); lower ground there only takes it
     * further in.</li>
     * <li>{@code plain}: 60 above the center of the flattest dry 64 × 64 block square of tile (0, 0)
     * (least spread of ground heights, first by z and then x), facing east 10° down.</li>
     * <li>{@code lake}: 110 blocks west of the deepest water of tile (0, 0) and 40 above its surface, facing
     * east 20° down, which brings the line of sight down to the surface there.</li>
     * <li>{@code ridge}: 400 blocks west of the highest fine sample of tile (0, 0), 20 above it (or above the
     * ground under the camera, if higher), facing east 5° down.</li>
     * <li>{@code edge-outward}: 100 blocks inside the west edge of tile (0, 0) on the spawn's z, 60 above the
     * ground, facing west 10° down.</li>
     * </ul>
     */
    static final class Baked {
        static final double EDGE_INSET = 264.5;
        static final int EDGE_PAST = 110;
        private static final int SQUARE = 64;

        private Baked() {
        }

        static Map<String, double[]> views(Map<String, byte[]> packs, Pos spawn) {
            final Map<String, double[]> views = new TreeMap<>();
            final byte[] whole = packs.get("0 0");
            if (whole == null) return views;
            final Drawn drawn = new Drawn(packs);
            final int z = (int) Math.floor(spawn.z());
            int highest = Integer.MIN_VALUE;
            for (int x = -EDGE_PAST; x <= EDGE_INSET; x++) highest = Math.max(highest, drawn.top(x, z));
            views.put("EDGE_VIEW", new double[]{EDGE_INSET, highest + 1 + 100, spawn.z(), 90, 15});
            views.put("edge-outward", new double[]{100.5, drawn.top(100, z) + 1 + 60, spawn.z(), 90, 10});
            plain(drawn, Drawn.reader(whole).header().baseLevel())
                    .ifPresent(at -> views.put("plain", new double[]{at[0] + 0.5, drawn.top(at[0], at[1]) + 1 + 60, at[1] + 0.5, -90, 10}));
            pick(whole, Layer.FINE, s -> s.hasFluid() ? s.fluidTop() - s.fluidBottom() : -1).ifPresent(at ->
                    views.put("lake", new double[]{at[0] - 110 + 0.5, drawn.top(at[0], at[1]) + 1 + 40, at[1] + 0.5, -90, 20}));
            pick(whole, Layer.FINE, Sample::top).ifPresent(at -> views.put("ridge", new double[]{at[0] - 400 + 0.5,
                    Math.max(drawn.top(at[0], at[1]), drawn.top(at[0] - 400, at[1])) + 1 + 20, at[1] + 0.5, -90, 5}));
            return views;
        }

        /** The center column of the flattest dry square of tile (0, 0), none when every square has water or a hole. */
        private static Optional<int[]> plain(Drawn drawn, int baseLevel) {
            final int step = 1 << baseLevel;
            int best = Integer.MAX_VALUE;
            int[] at = null;
            for (int z0 = 0; z0 < FarViewPack.TILE_BLOCKS; z0 += SQUARE) {
                for (int x0 = 0; x0 < FarViewPack.TILE_BLOCKS; x0 += SQUARE) {
                    int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
                    boolean dry = true;
                    square:
                    for (int z = z0; z < z0 + SQUARE; z += step) {
                        for (int x = x0; x < x0 + SQUARE; x += step) {
                            final Sample sample = drawn.at(x, z);
                            if (sample.hasFluid() || !sample.hasGround()) {
                                dry = false;
                                break square;
                            }
                            low = Math.min(low, sample.groundY());
                            high = Math.max(high, sample.groundY());
                        }
                    }
                    if (dry && high - low < best) {
                        best = high - low;
                        at = new int[]{x0 + SQUARE / 2, z0 + SQUARE / 2};
                    }
                }
            }
            return Optional.ofNullable(at);
        }
    }

    /**
     * The packs as the client draws them, the fine layer of a whole pack and the coarse layer of a coarse
     * prefix, each block inflated once as it is first asked for.
     */
    static final class Drawn {
        private final Map<String, FarViewPack.Reader> readers = new TreeMap<>();
        private final Map<String, List<Sample>> blocks = new HashMap<>();

        Drawn(Map<String, byte[]> packs) {
            packs.forEach((tile, pack) -> readers.put(tile, reader(pack)));
        }

        static FarViewPack.Reader reader(byte[] pack) {
            try {
                return FarViewPack.Reader.read(pack);
            } catch (FarViewPack.Invalid e) {
                throw new IllegalStateException(e);
            }
        }

        /** The sample covering column x, z, an empty one where no pack is. */
        Sample at(int x, int z) {
            final int tileX = Math.floorDiv(x, FarViewPack.TILE_BLOCKS), tileZ = Math.floorDiv(z, FarViewPack.TILE_BLOCKS);
            final FarViewPack.Reader reader = readers.get(tileX + " " + tileZ);
            if (reader == null) return Sample.EMPTY;
            final Layer layer = reader.coarseOnly() ? Layer.COARSE : Layer.FINE;
            final int level = layer == Layer.COARSE ? FarViewPack.COARSE_LEVEL : reader.header().baseLevel();
            final int sx = (x - tileX * FarViewPack.TILE_BLOCKS) >> level, sz = (z - tileZ * FarViewPack.TILE_BLOCKS) >> level;
            final int bx = sx / FarViewPack.BLOCK_SIDE, bz = sz / FarViewPack.BLOCK_SIDE;
            final List<Sample> block = blocks.computeIfAbsent(tileX + " " + tileZ + " " + bx + " " + bz, _ -> {
                try {
                    for (FarViewPack.Block present : reader.blocks(layer)) {
                        if (present.bx() == bx && present.bz() == bz) return reader.samples(present);
                    }
                } catch (FarViewPack.Invalid e) {
                    throw new IllegalStateException(e);
                }
                return Collections.nCopies(FarViewPack.BLOCK_SAMPLES, Sample.EMPTY);
            });
            return block.get(sz % FarViewPack.BLOCK_SIDE * FarViewPack.BLOCK_SIDE + sx % FarViewPack.BLOCK_SIDE);
        }

        /** The absolute y of the top block of column x, z; one below the world where it is empty. */
        int top(int x, int z) {
            final FarViewPack.Reader reader = readers.get(Math.floorDiv(x, FarViewPack.TILE_BLOCKS) + " "
                    + Math.floorDiv(z, FarViewPack.TILE_BLOCKS));
            return reader == null ? Integer.MIN_VALUE : reader.header().minY() + at(x, z).top();
        }
    }

    /**
     * {@code /expect.json}: the four points of V1 ④ with the block and the absolute y of the top of
     * their column as the packs hold it (the whole pack's fine sample in tile (0, 0), the coarse sample
     * of the tile around), and the camera spots ({@link Synthetic#views} and EDGE_VIEW 100 above the spawn,
     * {@link Baked#views} on the 3 × 3 tiles). On the 3 × 3 tiles the points are the highest fine sample
     * of tile (0, 0), its first sample with fluid and the highest sample of tile (-1, 0), coarse where it is
     * announced by its coarse prefix and fine in {@code serve-only}; a generator source hides nothing, so only
     * {@code baked-hidden} and {@code counter-hidden} have the hidden points ({@link #hiddenPoint}). With
     * {@code dayTime} 0 or more, {@code day_time} is it.
     */
    static String expectJson(String mode, Content content, Pos spawn, long dayTime) {
        final JsonObject json = new JsonObject();
        json.addProperty("mode", mode);
        json.addProperty("map_id", content.mapId());
        if (dayTime >= 0) json.addProperty("day_time", dayTime);
        final JsonArray points = new JsonArray();
        final byte[] whole = content.packs().get("0 0");
        final byte[] west = content.packs().get("-1 0");
        if (BAKED_MODES.contains(mode)) {
            if (whole != null) {
                pick(whole, Layer.FINE, Sample::top).ifPresent(at -> points.add(point("peak", whole, Layer.FINE, at)));
                pick(whole, Layer.FINE, s -> s.hasFluid() ? 1 : -1).ifPresent(at -> points.add(point("water", whole, Layer.FINE, at)));
            }
            if (west != null) {
                final Layer layer = Drawn.reader(west).coarseOnly() ? Layer.COARSE : Layer.FINE;
                pick(west, layer, Sample::top).ifPresent(at -> points.add(point("mountain", west, layer, at)));
            }
            if (mode.equals("baked-hidden") || mode.equals("counter-hidden")) {
                for (HiddenPoint hidden : HIDDEN_POINTS) points.add(hiddenPoint(content.full(), hidden));
            }
        } else {
            points.add(point("peak", Objects.requireNonNull(whole), Layer.FINE, Synthetic.PEAK));
            points.add(point("water", whole, Layer.FINE, Synthetic.WATER));
            points.add(point("hidden", whole, Layer.FINE, Synthetic.HIDDEN));
            points.add(point("mountain", Objects.requireNonNull(west), Layer.COARSE, Synthetic.MOUNTAIN));
        }
        json.add("points", points);
        final JsonObject views = new JsonObject();
        if (BAKED_MODES.contains(mode)) {
            Baked.views(content.packs(), spawn).forEach((name, view) -> views.add(name, view(view)));
        } else {
            views.add("EDGE_VIEW", view(new double[]{spawn.x(), spawn.y() + 100, spawn.z(), 90, 15}));
            Synthetic.views().forEach((name, view) -> views.add(name, view(view)));
        }
        json.add("views", views);
        json.addProperty("spawn", spawn.x() + " " + spawn.y() + " " + spawn.z());
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json) + "\n";
    }

    /**
     * A hidden point as the hidden rule of {@code API-M3-014} makes it on its layer: default stone of plains at
     * the lowest top of the ring of its area, the samples of that layer's grid around the cells the area covers,
     * an empty sample or one outside every pack counting 0. The ring is read from the whole packs of the release,
     * both layers of every tile, so a fine ring across a tile edge is read where the client gets only the coarse
     * prefix; a ring sample is never hidden, so the release that hides nothing gives the same height.
     *
     * @param full   the whole packs by {@code "<tile_x> <tile_z>"}
     * @param hidden the point
     * @return the point of {@code /expect.json}, with {@code biome}
     */
    static JsonObject hiddenPoint(Map<String, byte[]> full, HiddenPoint hidden) {
        final byte[] any = full.values().iterator().next();
        final FarViewPack.Header header = Drawn.reader(any).header();
        final int level = hidden.layer() == Layer.COARSE ? FarViewPack.COARSE_LEVEL : header.baseLevel();
        final List<int[]> cells = HIDDEN_AREAS.stream().map(area -> new int[]{area.x1() >> level, area.z1() >> level,
                area.x2() >> level, area.z2() >> level}).toList();
        int height = -1;
        for (int[] cell : cells) {
            if (!covers(cell, hidden.x() >> level, hidden.z() >> level)) continue;
            int lowest = Integer.MAX_VALUE;
            for (int x = cell[0] - 1; x <= cell[2] + 1; x++) {
                for (int z = cell[1] - 1; z <= cell[3] + 1; z++) {
                    final int cx = x, cz = z;
                    if (cells.stream().anyMatch(other -> covers(other, cx, cz))) continue;
                    final byte[] pack = full.get(Math.floorDiv(x << level, FarViewPack.TILE_BLOCKS) + " "
                            + Math.floorDiv(z << level, FarViewPack.TILE_BLOCKS));
                    final Sample sample = pack == null ? Sample.EMPTY : sampleAt(pack, hidden.layer(), x << level, z << level);
                    lowest = Math.min(lowest, Math.max(0, sample.top()));
                }
            }
            final int replaced = lowest == Integer.MAX_VALUE ? 0 : lowest;
            if (height < 0 || replaced < height) height = replaced;
        }
        if (height < 0) throw new IllegalArgumentException(hidden + " is in no hidden area");
        final JsonObject json = new JsonObject();
        json.addProperty("name", hidden.name());
        json.addProperty("x", hidden.x());
        json.addProperty("z", hidden.z());
        json.addProperty("tile_x", Math.floorDiv(hidden.x(), FarViewPack.TILE_BLOCKS));
        json.addProperty("tile_z", Math.floorDiv(hidden.z(), FarViewPack.TILE_BLOCKS));
        json.addProperty("layer", hidden.layer() == Layer.COARSE ? "coarse" : "fine");
        json.addProperty("block", Block.STONE.key().asString());
        json.addProperty("state", Block.STONE.stateId());
        json.addProperty("y", height + header.minY());
        json.addProperty("biome", "minecraft:plains");
        return json;
    }

    private static boolean covers(int[] cell, int x, int z) {
        return x >= cell[0] && x <= cell[2] && z >= cell[1] && z <= cell[3];
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
        json.addProperty("y", (fluid ? sample.fluidTop() : sample.groundY()) + Drawn.reader(pack).header().minY());
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
                if (path.equals("/rearm")) {
                    // half cuts its next response again: a second client run (counter-range) sees a cut of its own
                    cut.set(false);
                    send(exchange, 200, "text/plain; charset=utf-8", "rearmed\n".getBytes(StandardCharsets.UTF_8));
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
