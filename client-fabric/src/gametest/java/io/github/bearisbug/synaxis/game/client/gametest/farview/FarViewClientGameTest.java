package io.github.bearisbug.synaxis.game.client.gametest.farview;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.game.client.farview.FarViewCache;
import io.github.bearisbug.synaxis.game.client.farview.FarViewClient;
import io.github.bearisbug.synaxis.game.client.farview.FarViewConnection;
import io.github.bearisbug.synaxis.game.client.farview.FarViewSettings;
import io.github.bearisbug.synaxis.game.client.farview.dh.DhTestHook;
import io.github.bearisbug.synaxis.game.client.gametest.camera.SpikeCamera;
import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The client far view of {@code docs/DESIGN-M3.md} {@code REQ-M3-025} in the real client: the
 * container cases of §26 M3-G (容器验收: V1 and its counter-proofs, V2a, V3a, V6a, V8a), the §19
 * scenarios that need a running client and Distant Horizons, and the real machine modes of 真机验收
 * (V2b, V3b, V6b, V8b). Without {@code SYNAXIS_FARVIEW_SERVER} it does nothing, so the regression
 * after it runs as before.
 * <p>
 * {@code SYNAXIS_FARVIEW_SERVER} is the far view fixture's game port ({@code FarViewShardFixture} of
 * the server test sources), {@code SYNAXIS_FARVIEW_HTTP} its HTTP root ({@code /expect.json},
 * {@code /access.log}), {@code SYNAXIS_FARVIEW_MODE} a comma separated list of the modes below, run
 * in that order in one client. Each case writes one line {@code F<n> PASS|FAIL <evidence>} to
 * {@code synaxis-gametest/farview-report.txt}; readings that only get recorded are {@code #} lines.
 * DH is reached only through {@code DhTestHook}, and only when Fabric loaded DH.
 */
public final class FarViewClientGameTest implements FabricClientGameTest {

    private static final int SHOT_WIDTH = 1280;
    private static final int SHOT_HEIGHT = 720;
    private static final int CONNECT_TICKS = 20 * 120;
    /** llvmpipe and DH's threads share the container's cores: a bake gets 20 minutes. */
    private static final int BAKE_TICKS = 20 * 60 * 20;
    private static final int QUERY_TICKS = 20 * 60;
    /** The side of the centre block of EDGE_VIEW: the pixels around the centre of the line of sight, 64 square. */
    private static final int CENTRE = 64;
    private static final Pattern BAKED = Pattern.compile(
            "view (\\w+) baked in (\\d+) ms: (\\d+) sources, (\\d+) B; written (\\d+) ms, visible (\\d+) ms after the announcement");
    private static final Pattern SAVE_FOLDER = Pattern.compile("Save folder overridden from \\[(.*)] -> \\[(.*)]\\.");

    @Override
    public void runTest(ClientGameTestContext context) {
        final String server = env("SYNAXIS_FARVIEW_SERVER", "");
        if (server.isBlank()) return;
        final Run run = new Run(context, server, env("SYNAXIS_FARVIEW_HTTP", ""));
        try {
            context.waitForScreen(TitleScreen.class);
            for (String mode : env("SYNAXIS_FARVIEW_MODE", "v1").split(",")) {
                final String name = mode.trim();
                if (name.isEmpty()) continue;
                run.note("mode " + name + " from " + LocalTime.now().withNano(0));
                try {
                    run.mode(name);
                } catch (Throwable t) {
                    run.note("mode " + name + " aborted: " + stack(t));
                } finally {
                    run.leave();
                    run.resetHooks();
                    run.write();
                }
            }
        } finally {
            run.write();
        }
    }

    private static String env(String name, String fallback) {
        final String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String stack(Throwable t) {
        final StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return out.toString().lines().limit(12).reduce((a, b) -> a + " | " + b).orElse(String.valueOf(t));
    }

    /** One client run against one fixture: its report, its fixture, its camera spots. */
    private static final class Run {
        private final ClientGameTestContext context;
        private final String server;
        private final String http;
        private final Path gameDir = FabricLoader.getInstance().getGameDir();
        private final boolean dh = FabricLoader.getInstance().isModLoaded(FarViewClient.DH_MOD_ID);
        private final boolean iris = FabricLoader.getInstance().isModLoaded("iris");
        private final Map<String, String> lines = new LinkedHashMap<>();
        private final List<String> notes = new ArrayList<>();
        private final HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(Duration.ofSeconds(10)).version(HttpClient.Version.HTTP_1_1).build();
        private @Nullable JsonObject expect;
        private Vec3 spawn = Vec3.ZERO;
        private int minY;
        /** The shots of the last V1 before its bake, for the reconnect of ⑦ and the modes after it. */
        private final Map<String, Path> before = new LinkedHashMap<>();

        Run(ClientGameTestContext context, String server, String http) {
            this.context = context;
            this.server = server;
            this.http = http;
        }

        void mode(String mode) {
            switch (mode) {
                case "v1" -> v1();
                case "v1-noparent" -> noParent();
                case "v1-writable" -> writable();
                case "v1-coarse-only" -> coarseOnly();
                case "marker" -> marker();
                case "cancel" -> cancel();
                case "late-cancel" -> lateCancel();
                case "tiers" -> tiers();
                case "none" -> none();
                case "absent" -> absent();
                case "break-sha" -> breakSha();
                case "half" -> half();
                case "v2", "bake-timing" -> timing(mode);
                case "v3" -> shaders();
                case "v6" -> rtsHoles();
                case "v8" -> memory();
                case "toggle" -> toggle();
                case "shots" -> realShots();
                case "rts-shots" -> realRtsShots();
                case "bench" -> bench();
                default -> note("unknown mode " + mode);
            }
        }

        // ---- The report

        void line(String id, boolean pass, String evidence) {
            lines.put(id, id + (pass ? " PASS " : " FAIL ") + evidence);
            write();
        }

        void note(String text) {
            notes.add("# " + text);
            write();
        }

        void write() {
            final Path dir = gameDir.resolve("synaxis-gametest");
            try {
                Files.createDirectories(dir);
                final List<String> out = new ArrayList<>(lines.values());
                out.addAll(notes);
                Files.write(dir.resolve("farview-report.txt"), out, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("could not write the far view report", e);
            }
        }

        // ---- The fixture

        String get(String path) {
            try {
                final HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(http + path))
                        .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) throw new IllegalStateException(path + " answered " + response.statusCode());
                return response.body();
            } catch (IOException e) {
                throw new IllegalStateException(http + path + ": " + e, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        List<String> accessLog() {
            return get("/access.log").lines().filter(line -> !line.isBlank()).toList();
        }

        JsonObject expect() {
            if (expect == null) {
                expect = JsonParser.parseString(get("/expect.json")).getAsJsonObject();
                final String[] at = expect.get("spawn").getAsString().split(" ");
                spawn = new Vec3(Double.parseDouble(at[0]), Double.parseDouble(at[1]), Double.parseDouble(at[2]));
                note("fixture mode " + expect.get("mode").getAsString() + ", map " + expect.get("map_id").getAsString()
                        + ", spawn " + expect.get("spawn").getAsString());
            }
            return expect;
        }

        // ---- The client

        List<String> log() {
            try {
                return Files.readAllLines(gameDir.resolve("logs").resolve("latest.log"), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return List.of();
            }
        }

        List<String> logSince(int from) {
            final List<String> all = log();
            return from >= all.size() ? List.of() : all.subList(from, all.size());
        }

        /**
         * Joins the server, six attempts 10 s apart: from the container a disconnect reaches the server only as a
         * keep-alive timeout (Docker Desktop's forwarding keeps the host side of the socket open), so a reconnect
         * within about 25 s of the last one is refused with {@code synaxis:duplicate-login}.
         */
        boolean connect() {
            expect();
            for (int attempt = 1; attempt <= 6; attempt++) {
                context.runOnClient(client -> {
                    client.options.renderDistance().set(8);
                    client.options.cloudStatus().set(CloudStatus.OFF);
                });
                context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client,
                        ServerAddress.parseString(server), new ServerData("farview", server, ServerData.Type.OTHER), false, null));
                final boolean entered = until(() -> context.computeOnClient(client -> inWorld(client)
                        || client.gui.screen() instanceof DisconnectedScreen), CONNECT_TICKS)
                        && context.computeOnClient(Run::inWorld);
                if (entered) {
                    context.runOnClient(client -> {
                        minY = client.level.getMinY();
                        client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                        if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
                        client.player.getAbilities().flying = true;
                    });
                    return true;
                }
                note("attempt " + attempt + " not in the world of " + server + ": " + context.computeOnClient(client ->
                        client.gui.screen() == null ? "no screen" : client.gui.screen().getNarrationMessage().getString()));
                context.setScreen(TitleScreen::new);
                context.waitForScreen(TitleScreen.class);
                context.waitTicks(200);
            }
            return false;
        }

        private static boolean inWorld(Minecraft client) {
            return client.level != null && client.player != null;
        }

        void leave() {
            SpikeCamera.set(null);
            if (context.computeOnClient(client -> client.level != null)) {
                context.runOnClient(client -> client.disconnect(new TitleScreen(), false));
            } else if (!context.computeOnClient(client -> client.gui.screen() instanceof TitleScreen)) {
                context.setScreen(TitleScreen::new);
            }
            context.waitForScreen(TitleScreen.class);
            // DH closes the world, its threads and its library connections as the connection ends
            context.waitTicks(40);
        }

        void resetHooks() {
            if (dh) DhTestHook.reset();
        }

        boolean until(BooleanSupplier condition, int ticks) {
            for (int waited = 0; waited <= ticks; waited += 10) {
                if (condition.getAsBoolean()) return true;
                context.waitTicks(10);
            }
            return condition.getAsBoolean();
        }

        @Nullable FarViewConnection connection() {
            return FarViewClient.last();
        }

        /**
         * Until the connection's bake thread finished (a bake, or the check of a library whose marker matched) or the
         * far view went off before one started; true for a ready library.
         */
        boolean ready() {
            until(() -> {
                final FarViewConnection c = connection();
                return c != null && (c.settled() || c.failure() != null);
            }, BAKE_TICKS);
            final FarViewConnection c = connection();
            context.waitTicks(20);
            return c != null && c.state() == FarViewConnection.State.READY;
        }

        FarViewCache cache() {
            return new FarViewCache(gameDir.resolve("synaxis").resolve("farview"));
        }

        void clearCache() {
            final Path root = cache().root();
            if (!Files.exists(root)) return;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException e) {
                throw new IllegalStateException("could not clear " + root, e);
            }
        }

        void radius(int radius) {
            try {
                final Path config = FabricLoader.getInstance().getConfigDir();
                Files.createDirectories(config);
                Files.writeString(config.resolve(FarViewSettings.FILE), FarViewSettings.KEY + "=" + radius + "\n");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** A DH query on a platform thread of the test's own, never the gametest or the render thread. */
        <T> @Nullable T query(Supplier<T> query) {
            final CompletableFuture<T> answer = new CompletableFuture<>();
            Thread.ofPlatform().daemon().name("farview-test-query").start(() -> {
                try {
                    answer.complete(query.get());
                } catch (Throwable t) {
                    answer.completeExceptionally(t);
                }
            });
            until(answer::isDone, QUERY_TICKS);
            if (!answer.isDone()) return null;
            try {
                return answer.join();
            } catch (RuntimeException e) {
                note("query failed: " + e);
                return null;
            }
        }

        long count(String where) {
            final Long n = query(() -> DhTestHook.count(where));
            return n == null ? -1 : n;
        }

        Map<String, String> graphics() {
            return context.computeOnClient(_ -> DhTestHook.graphics());
        }

        // ---- The camera

        SpikeCamera.Settings at(double x, double y, double z, float yaw, float pitch) {
            return new SpikeCamera.Settings(SpikeCamera.Mode.PERSPECTIVE, new Vec3(x, y, z), yaw, pitch, 0f, 96f);
        }

        /** FAR_VIEW: 40 above the spawn, facing east, the horizon across the frame. */
        SpikeCamera.Settings farView() {
            return at(spawn.x, spawn.y + 40, spawn.z, -90f, 5f);
        }

        /** LOD_VIEW: 600 blocks east of the player at y 190, looking down at nothing but LOD. */
        SpikeCamera.Settings lodView() {
            return at(spawn.x + 600, 190, spawn.z, -90f, 25f);
        }

        SpikeCamera.Settings view(String name) {
            final JsonObject v = expect().getAsJsonObject("views").getAsJsonObject(name);
            return at(v.get("x").getAsDouble(), v.get("y").getAsDouble(), v.get("z").getAsDouble(), v.get("yaw").getAsFloat(),
                    v.get("pitch").getAsFloat());
        }

        Path shot(String name, SpikeCamera.Settings view, int settle) {
            context.runOnClient(_ -> SpikeCamera.set(view));
            context.waitTicks(settle);
            return context.takeScreenshot(TestScreenshotOptions.of("farview-" + name).withSize(SHOT_WIDTH, SHOT_HEIGHT));
        }

        /** Shots every 40 ticks until two in a row differ in under 0.1% of the pixels, at most {@code ticks}. */
        Path stable(String name, SpikeCamera.Settings view, int ticks) {
            return drawn(name, view, null, ticks);
        }

        /**
         * Shots every 40 ticks until the picture differs from {@code empty} in over 1% of its pixels and matches one of
         * the two before it to within 0.1%, at most {@code ticks}. Two empty frames are as alike as two finished ones,
         * so a view that waits for DH's render buffers needs the empty one to tell them apart; DH's finished LOD can
         * alternate between two frames under llvmpipe, which the second-to-last one catches.
         */
        Path drawn(String name, SpikeCamera.Settings view, @Nullable Path empty, int ticks) {
            final BufferedImage nothing = empty == null ? null : Shots.read(empty);
            BufferedImage before = null;
            Path last = shot(name + "-0", view, 40);
            for (int waited = 40, i = 1; waited < ticks; waited += 40, i++) {
                final Path next = shot(name + "-" + i, view, 40);
                final BufferedImage a = Shots.read(last), b = Shots.read(next);
                last = next;
                final boolean content = nothing == null || Shots.changed(nothing, b, Shots.Box.whole(b)) > 0.01;
                final boolean settled = Shots.changed(a, b, Shots.Box.whole(a)) < 0.001
                        || before != null && Shots.changed(before, b, Shots.Box.whole(b)) < 0.001;
                if (content && settled) break;
                before = a;
            }
            return last;
        }

        /**
         * Connects with the bake held at its start, takes LOD_VIEW while DH has nothing to draw, then lets the bake go.
         *
         * @return the empty LOD_VIEW and the milliseconds the bake was held, null when not in the world
         */
        Map.@Nullable Entry<Path, Long> heldBake(String name) {
            final CountDownLatch hold = new CountDownLatch(1);
            DhTestHook.holdStart = hold;
            if (!connect()) return null;
            until(() -> {
                final FarViewConnection c = connection();
                return c != null && (c.state() == FarViewConnection.State.BAKING || c.failure() != null);
            }, CONNECT_TICKS);
            final long heldFrom = System.nanoTime();
            final Path empty = shot(name + "-lod-empty", lodView(), 60);
            final long held = FarViewConnection.millisSince(heldFrom);
            hold.countDown();
            return Map.entry(empty, held);
        }

        // ---- The player

        /** Flies {@code blocks} east and back, two blocks a tick at y 180, so the server sends the chunks on the way. */
        void walk(int blocks) {
            final Vec3 from = context.computeOnClient(client -> client.player.position());
            for (int step = 0; step <= blocks; step += 2) {
                final double x = from.x + step;
                context.runOnClient(client -> client.player.setPos(x, 180, from.z));
                context.waitTick();
            }
            context.waitTicks(20);
            note("walked to " + context.computeOnClient(client -> client.player.position()) + " from " + from);
            for (int step = blocks; step >= 0; step -= 2) {
                final double x = from.x + step;
                context.runOnClient(client -> client.player.setPos(x, 180, from.z));
                context.waitTick();
            }
            context.runOnClient(client -> client.player.setPos(from.x, from.y, from.z));
            context.waitTicks(40);
        }

        // ---- What the packs say

        /** Sources the bake hands over for the connection's announcement: fine blocks, and coarse blocks × 16. */
        long expectedSources(FarViewConnection c, int perCoarseBlock) {
            long sources = 0;
            for (FarViewAnnouncement.Tile tile : c.announcement().tiles()) {
                final FarViewPack.Reader reader = reader(tile);
                sources += tile.layer() == FarViewAnnouncement.LAYER_FULL ? reader.blocks(FarViewPack.Layer.FINE).size()
                        : (long) reader.blocks(FarViewPack.Layer.COARSE).size() * perCoarseBlock;
            }
            return sources;
        }

        FarViewPack.Reader reader(FarViewAnnouncement.Tile tile) {
            try {
                return FarViewPack.Reader.read(Files.readAllBytes(cache().pack(FarViewConnection.hex(tile))));
            } catch (IOException | FarViewPack.Invalid e) {
                throw new IllegalStateException(e);
            }
        }

        Optional<FarViewCache.Marker> marker(FarViewConnection c) {
            return cache().marker(c.viewKey());
        }

        @Nullable String find(List<String> log, String text) {
            for (String line : log) if (line.contains(text)) return line;
            return null;
        }

        int index(List<String> log, String text) {
            for (int i = 0; i < log.size(); i++) if (log.get(i).contains(text)) return i;
            return -1;
        }

        // ---- V1: W2 in the headless client (§26 M3-G 容器验收 4)

        void v1() {
            clearCache();
            radius(FarViewSettings.DEFAULT_RADIUS);
            final int logFrom = log().size();
            final CountDownLatch hold = new CountDownLatch(1);
            DhTestHook.holdStart = hold;
            if (!connect()) {
                line("F1", false, "not in the world");
                return;
            }
            final boolean baking = until(() -> {
                final FarViewConnection c = connection();
                return c != null && (c.state() == FarViewConnection.State.BAKING || c.failure() != null);
            }, CONNECT_TICKS);
            note("V1 waiting for the bake to start: " + baking + ", state " + Objects.requireNonNull(connection()).state());
            before.put("far", shot("v1-far-before", farView(), 80));
            before.put("edge", shot("v1-edge-before", view("EDGE_VIEW"), 40));
            before.put("lod", shot("v1-lod-before", lodView(), 40));
            hold.countDown();
            // The HUD line while the bake runs (API-M3-015 进度与日志), the one shot with the HUD shown
            context.waitTicks(100);
            context.runOnClient(client -> {
                SpikeCamera.set(null);
                if (client.gui.hud.isHidden()) client.gui.hud.toggle();
            });
            context.waitTicks(20);
            final String hud = context.computeOnClient(_ -> {
                final FarViewConnection c = connection();
                final Component text = c == null ? null : c.hudLine();
                return text == null ? "none" : text.getString();
            });
            shot("v1-hud", at(spawn.x, spawn.y + 2, spawn.z, -90f, 10f), 1);
            context.runOnClient(client -> {
                if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
            });
            note("V1 HUD line during the bake: " + hud);
            final boolean ready = ready();
            final FarViewConnection c = Objects.requireNonNull(connection());
            final String key = c.viewKey();
            final List<String> log = logSince(logFrom);
            note("V1 state " + c.state() + (c.failure() == null ? "" : " failure " + c.failure()) + ", view " + key
                    + ", download " + c.downloadMillis() + " ms, timings " + c.timings());

            // ① the library is pinned to synaxis/farview/dh/<view_key>/, DH's own folder holds none
            final String saveLine = find(log, "Save folder overridden from");
            final Matcher save = saveLine == null ? null : SAVE_FOLDER.matcher(saveLine);
            final String target = save != null && save.find() ? save.group(2) : "none";
            final String levelFolder = query(DhTestHook::saveFolder);
            final List<Path> defaultLibraries = libraries(gameDir.resolve("Distant_Horizons_server_data"));
            final boolean pinned = target.replace('\\', '/').endsWith("synaxis/farview/dh/" + key)
                    && levelFolder != null && Path.of(levelFolder).toAbsolutePath().normalize()
                    .equals(Path.of(target).toAbsolutePath().normalize());
            line("F1", pinned && defaultLibraries.isEmpty(), "override target " + target + ", level getDhSaveFolder " + levelFolder
                    + ", libraries under Distant_Horizons_server_data " + defaultLibraries);

            // ② read only, and the two overrides set through the API
            final Boolean readOnly = query(DhTestHook::readOnlyNow);
            final Map<String, String> graphics = graphics();
            final String distance = graphics.get("chunkRenderDistance"), resolution = graphics.get("maxHorizontalResolution");
            line("F2", Boolean.TRUE.equals(readOnly) && distance.contains("api=256 ") && distance.endsWith("overridable=true")
                            && resolution.contains("api=FOUR_BLOCKS ") && resolution.endsWith("overridable=true"),
                    "getReadOnly " + readOnly + ", chunkRenderDistance " + distance + ", maxHorizontalResolution " + resolution);

            // ③ the bake line and the marker's sources
            final String bakedLine = find(log, "baked in");
            final Matcher baked = bakedLine == null ? null : BAKED.matcher(bakedLine);
            final boolean hasBaked = baked != null && baked.find();
            final long sources = ready ? expectedSources(c, 16) : -1;
            final Optional<FarViewCache.Marker> marker = marker(c);
            line("F3", ready && hasBaked && marker.isPresent() && marker.get().sources() == sources
                            && Long.parseLong(baked.group(6)) >= Long.parseLong(baked.group(2)),
                    (hasBaked ? "ms " + baked.group(2) + " write_ms " + baked.group(5) + " visible_ms " + baked.group(6) + " db_bytes "
                            + baked.group(4) : "no bake line") + "; marker sources " + marker.map(FarViewCache.Marker::sources)
                            .map(String::valueOf).orElse("none") + ", fine blocks + coarse blocks × 16 = " + sources);

            // ④ the rows on DH's own connection, and the four points read back
            final long base = count("DetailLevel = 2"), parents = count("ApplyToParent = 1"),
                    children = count("ApplyToChildren = 1"), four = count("DetailLevel = 4");
            final List<String> points = new ArrayList<>();
            boolean pointsOk = true;
            for (JsonElement element : expect().getAsJsonArray("points")) {
                final JsonObject point = element.getAsJsonObject();
                final int x = point.get("x").getAsInt(), z = point.get("z").getAsInt();
                final Map<String, Object> top = query(() -> DhTestHook.topPoint(x, z, 2));
                final int expectedIndex = point.get("y").getAsInt() - minY;
                final boolean ok = top != null && !top.isEmpty() && ((Integer) top.get("state")) == point.get("state").getAsInt()
                        && ((Integer) top.get("top")) - 1 == expectedIndex;
                pointsOk &= ok;
                points.add(point.get("name").getAsString() + "@" + x + "," + z + " expected " + point.get("block").getAsString()
                        + " index " + expectedIndex + " got " + top + (ok ? " ok" : " WRONG"));
            }
            line("F4", base == sources && parents == 0 && children == 0 && four > 0 && pointsOk,
                    "DetailLevel=2 " + base + " of " + sources + ", ApplyToParent=1 " + parents + ", ApplyToChildren=1 " + children
                            + ", DetailLevel=4 " + four + "; " + String.join("; ", points));

            // ⑤ the near chunks stay out of the library
            final long levelZeroBefore = count("DetailLevel = 0");
            walk(200);
            final long levelZeroAfter = count("DetailLevel = 0");
            line("F5", levelZeroBefore == 0 && levelZeroAfter == 0,
                    "DetailLevel=0 before the walk " + levelZeroBefore + ", after 200 blocks and back " + levelZeroAfter);

            // ⑥ the far view, the LOD view and the edge view drawn
            final Path far = stable("v1-far-after", farView(), 1200);
            final Path lod = shot("v1-lod-after", lodView(), 40);
            final Path edge = shot("v1-edge-after", view("EDGE_VIEW"), 40);
            final BufferedImage farBefore = Shots.read(before.get("far")), farAfter = Shots.read(far);
            final double horizon = Shots.changed(farBefore, farAfter, Shots.Box.rows(farBefore, 200, 460));
            final BufferedImage lodBefore = Shots.read(before.get("lod")), lodAfter = Shots.read(lod);
            final double lodChanged = Shots.changed(lodBefore, lodAfter, Shots.Box.whole(lodAfter));
            final BufferedImage edgeBefore = Shots.read(before.get("edge")), edgeAfter = Shots.read(edge);
            final Shots.Box centre = Shots.Box.centre(edgeAfter, CENTRE);
            final double edgeChanged = Shots.changed(edgeBefore, edgeAfter, centre);
            line("F6", horizon > 0.01 && lodChanged > 0.25 && edgeChanged > 0.5,
                    "FAR_VIEW horizon rows 200..459 changed " + Shots.percent(horizon) + "; LOD_VIEW changed "
                            + Shots.percent(lodChanged) + " (mean " + Shots.hex(Shots.mean(lodAfter, Shots.Box.whole(lodAfter)))
                            + "); EDGE_VIEW centre " + centre + " changed " + Shots.percent(edgeChanged) + " (mean before "
                            + Shots.hex(Shots.mean(edgeBefore, centre)) + ", after " + Shots.hex(Shots.mean(edgeAfter, centre)) + ")");

            // ⑦ the announcement before the save folder; a reconnect downloads and bakes nothing
            final int announced = index(log, FarViewClient.PREFIX + "announcement received");
            final int overridden = index(log, "Save folder overridden from");
            leave();
            final int accessBefore = accessLog().size();
            final int logAgain = log().size();
            final boolean back = connect();
            final long inWorldAt = System.nanoTime();
            boolean lodSoon = false;
            int ticks = 0;
            for (; back && ticks <= 200; ticks += 20) {
                final Path again = shot("v1-far-reconnect-" + ticks, farView(), 20);
                if (Shots.changed(farBefore, Shots.read(again), Shots.Box.rows(farBefore, 200, 460)) > 0.01) {
                    lodSoon = true;
                    break;
                }
            }
            final boolean readyLine = until(() -> find(logSince(logAgain), "view " + key + " ready") != null, 600);
            final List<String> logAfter = logSince(logAgain);
            final int accessAfter = accessLog().size();
            line("F7", announced >= 0 && overridden > announced && back && accessAfter == accessBefore && readyLine
                            && find(logAfter, "baked in") == null && lodSoon,
                    "announcement at log line " + announced + ", save folder at " + overridden + "; reconnect: access.log "
                            + accessBefore + " -> " + accessAfter + " lines, ready line " + readyLine + ", baked in "
                            + (find(logAfter, "baked in") != null) + ", FAR_VIEW shows LOD " + (lodSoon ? "after " + ticks + " ticks"
                            : "not within 200 ticks") + " (" + FarViewConnection.millisSince(inWorldAt) + " ms)");
        }

        private static List<Path> libraries(Path dir) {
            if (!Files.isDirectory(dir)) return List.of();
            try (Stream<Path> files = Files.walk(dir)) {
                return files.filter(path -> path.getFileName().toString().equals("DistantHorizons.sqlite")).toList();
            } catch (IOException e) {
                return List.of(dir);
            }
        }

        // ---- V1 counter-proofs

        /** v1-noparent: without applyToParent DH never fills the coarser levels. */
        void noParent() {
            clearCache();
            DhTestHook.applyToParent = false;
            if (!connect()) {
                line("F8", false, "not in the world");
                return;
            }
            final boolean ready = ready();
            final long two = count("DetailLevel = 2"), three = count("DetailLevel = 3"), four = count("DetailLevel = 4");
            line("F8", four == 0 && three == 0 && two > 0, "counter-proof v1-noparent: ready " + ready + ", DetailLevel=2 " + two
                    + ", DetailLevel=3 " + three + ", DetailLevel=4 " + four + " (0 expected without applyToParent)");
            leave();
            clearCache();
        }

        /** v1-writable: without read only DH stores the near chunks it is sent. */
        void writable() {
            clearCache();
            DhTestHook.readOnly = false;
            if (!connect()) {
                line("F9", false, "not in the world");
                return;
            }
            ready();
            final Boolean readOnly = query(DhTestHook::readOnlyNow);
            final long levelZeroBefore = count("DetailLevel = 0");
            walk(200);
            final long levelZeroAfter = count("DetailLevel = 0");
            final FarViewConnection c = Objects.requireNonNull(connection());
            line("F9", levelZeroAfter > 0, "counter-proof v1-writable: getReadOnly " + readOnly + ", DetailLevel=0 before the walk "
                    + levelZeroBefore + ", after " + levelZeroAfter + " (above 0 expected); the bake ended " + c.state()
                    + (c.failure() == null ? "" : " " + c.failure()));
            leave();
            clearCache();
        }

        /** v1-coarse-only: the coarse layer at its own level only, so nothing is drawn near the region's edge. */
        void coarseOnly() {
            clearCache();
            DhTestHook.expandCoarse = false;
            final CountDownLatch hold = new CountDownLatch(1);
            DhTestHook.holdStart = hold;
            if (!connect()) {
                line("F10", false, "not in the world");
                return;
            }
            until(() -> {
                final FarViewConnection c = connection();
                return c != null && (c.state() == FarViewConnection.State.BAKING || c.failure() != null);
            }, CONNECT_TICKS);
            final Path edgeBefore = shot("coarse-only-edge-before", view("EDGE_VIEW"), 80);
            hold.countDown();
            final boolean ready = ready();
            final long four = count("DetailLevel = 4"), two = count("DetailLevel = 2");
            final Path edge = stable("coarse-only-edge-after", view("EDGE_VIEW"), 600);
            final BufferedImage a = Shots.read(edgeBefore), b = Shots.read(edge);
            final Shots.Box centre = Shots.Box.centre(b, CENTRE);
            final double changed = Shots.changed(a, b, centre);
            line("F10", changed < 0.1, "counter-proof v1-coarse-only: ready " + ready + ", DetailLevel=2 " + two + ", DetailLevel=4 "
                    + four + "; EDGE_VIEW centre " + centre + " changed " + Shots.percent(changed) + " (one sky colour expected), mean "
                    + Shots.hex(Shots.mean(b, centre)));
            leave();
            clearCache();
        }

        // ---- §19 scenarios

        /** A ready library whose marker says one source more: rebaked from the cache, nothing downloaded. */
        void marker() {
            radius(FarViewSettings.DEFAULT_RADIUS);
            FarViewConnection c = connection();
            if (c == null || !c.available() || cache().marker(c.viewKey()).isEmpty()) {
                clearCache();
                if (!connect() || !ready()) {
                    line("F11", false, "no ready library to start from");
                    return;
                }
                leave();
                c = Objects.requireNonNull(connection());
            }
            final String key = c.viewKey();
            final FarViewCache.Marker marker = cache().marker(key).orElseThrow();
            try {
                cache().writeMarker(new FarViewCache.Marker(marker.format(), marker.viewKey(), marker.dhVersion(),
                        marker.bakeCodeVersion(), marker.tiles(), marker.sources() + 1, marker.bakeMs(), marker.bakedAt(),
                        marker.lastUsed()));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            final int access = accessLog().size();
            final int logFrom = log().size();
            if (!connect()) {
                line("F11", false, "not in the world");
                return;
            }
            final boolean ready = ready();
            final List<String> log = logSince(logFrom);
            final String mismatch = find(log, "view " + key + " library does not match its marker");
            final long rows = count("DetailLevel = 2");
            final long sources = cache().marker(key).map(FarViewCache.Marker::sources).orElse(-1L);
            final int accessAfter = accessLog().size();
            line("F11", mismatch != null && ready && rows == sources && sources == marker.sources() && accessAfter == access,
                    "marker sources set to " + (marker.sources() + 1) + "; " + (mismatch == null ? "no mismatch line"
                            : mismatch.substring(mismatch.indexOf("view "))) + "; rebaked " + ready + ", marker sources " + sources
                            + ", DetailLevel=2 " + rows + "; access.log " + access + " -> " + accessAfter + " lines");
        }

        /** A disconnect while the bake runs stops it within 5 s; the next connection bakes from the start. */
        void cancel() {
            clearCache();
            if (!connect()) {
                line("F12", false, "not in the world");
                return;
            }
            final boolean underWay = until(() -> {
                final FarViewConnection c = connection();
                return c != null && c.state() == FarViewConnection.State.BAKING && c.progressDone() > 0;
            }, BAKE_TICKS);
            final FarViewConnection first = Objects.requireNonNull(connection());
            final long handed = first.progressDone();
            final Thread bake = DhTestHook.bakeThread;
            final long disconnected = System.nanoTime();
            context.runOnClient(client -> client.disconnect(new TitleScreen(), false));
            final boolean stopped = until(() -> first.settled(), 20 * 30);
            // From the cancel flag to the bake thread's last statement, both stamped by the client itself: the gametest
            // ticks stall while DH closes its world, so a tick count would measure DH's shutdown, not the bake
            final long stopMillis = first.cancelledNanos() == 0 || first.settledNanos() == 0 ? -1
                    : TimeUnit.NANOSECONDS.toMillis(first.settledNanos() - first.cancelledNanos());
            final long sinceDisconnect = FarViewConnection.millisSince(disconnected);
            context.waitForScreen(TitleScreen.class);
            context.waitTicks(40);
            final boolean noMarker = cache().marker(first.viewKey()).isEmpty();
            final boolean back = connect();
            final boolean ready = back && ready();
            final long rows = count("DetailLevel = 2");
            final long sources = cache().marker(first.viewKey()).map(FarViewCache.Marker::sources).orElse(-1L);
            line("F12", underWay && stopped && stopMillis >= 0 && stopMillis <= 5000 && noMarker && ready && rows == sources,
                    "disconnected after " + handed + " sources handed over; the bake thread stopped " + stopped + " in " + stopMillis
                            + " ms after the cancel flag (" + sinceDisconnect + " ms after the disconnect by the test's clock, thread alive "
                            + (bake != null && bake.isAlive()) + "); marker after the cancel " + !noMarker + "; reconnected and ready " + ready + ", marker sources "
                            + sources + ", DetailLevel=2 " + rows);
        }

        /** The cancel arriving too late: the write check holds back the marker of a library DH never finished. */
        void lateCancel() {
            final String first = lateCancel(true);
            if (first == null) return;
            resetHooks();
            final boolean back = connect();
            final boolean ready = back && ready();
            final FarViewConnection c = Objects.requireNonNull(connection());
            final long rows = count("DetailLevel = 2");
            final long sources = cache().marker(c.viewKey()).map(FarViewCache.Marker::sources).orElse(-1L);
            line("F13", first.startsWith("ok") && ready && rows == sources, first + "; reconnected and rebaked " + ready
                    + ", marker sources " + sources + ", DetailLevel=2 " + rows);
            leave();
            resetHooks();
            final String counter = lateCancel(false);
            line("F14", counter != null && counter.contains("marker true"),
                    "counter-proof without the write check: " + counter);
            clearCache();
        }

        /** One late cancel: held before the last source, disconnected, released; what it left. */
        @Nullable String lateCancel(boolean writeCheck) {
            clearCache();
            final CountDownLatch hold = new CountDownLatch(1);
            final CountDownLatch reached = new CountDownLatch(1);
            DhTestHook.holdBeforeLast = hold;
            DhTestHook.reachedLast = reached;
            DhTestHook.ignoreCancel = true;
            DhTestHook.writeCheck = writeCheck;
            final int logFrom = log().size();
            if (!connect()) {
                line(writeCheck ? "F13" : "F14", false, "not in the world");
                return null;
            }
            final boolean held = until(() -> reached.getCount() == 0, BAKE_TICKS);
            final FarViewConnection c = Objects.requireNonNull(connection());
            final Thread bake = DhTestHook.bakeThread;
            leave();
            hold.countDown();
            final boolean ended = until(() -> bake == null || !bake.isAlive(), 20 * 60);
            final String incomplete = find(logSince(logFrom), "view " + c.viewKey() + " incomplete");
            final boolean marker = cache().marker(c.viewKey()).isPresent();
            final String what = "held before the last source " + held + ", released after the disconnect, bake ended " + ended
                    + "; " + (incomplete == null ? "no incomplete line" : incomplete.substring(incomplete.indexOf("view ")))
                    + "; marker " + marker;
            return (writeCheck && held && ended && incomplete != null && !marker ? "ok " : "") + what;
        }

        /** The three settings of farview.radius. */
        void tiers() {
            final List<String> seen = new ArrayList<>();
            boolean pass = true;
            for (int radius : new int[]{FarViewSettings.DEFAULT_RADIUS, FarViewSettings.LOW_RADIUS}) {
                radius(radius);
                if (!connect()) {
                    line("F15", false, "not in the world at " + radius);
                    return;
                }
                context.waitTicks(40);
                final Map<String, String> graphics = graphics();
                final boolean ok = graphics.get("chunkRenderDistance").contains("api=" + radius / 16 + " ")
                        && graphics.get("maxHorizontalResolution").contains("api=FOUR_BLOCKS ");
                pass &= ok;
                seen.add(radius + ": chunkRenderDistance " + graphics.get("chunkRenderDistance") + ", maxHorizontalResolution "
                        + graphics.get("maxHorizontalResolution"));
                leave();
            }
            clearCache();
            radius(FarViewSettings.OFF);
            final int access = accessLog().size();
            if (!connect()) {
                line("F15", false, "not in the world at 0");
                return;
            }
            context.waitTicks(200);
            final Map<String, String> graphics = graphics();
            final int accessAfter = accessLog().size();
            final FarViewConnection c = connection();
            final String folder = query(DhTestHook::saveFolder);
            final long rows = count("DetailLevel >= 0");
            final boolean off = noOverride(graphics) && accessAfter == access && c != null && !c.available()
                    && pinnedOff(folder) && rows == 0;
            seen.add("0: far view available " + (c != null && c.available()) + ", access.log " + access + " -> " + accessAfter
                    + " lines, library " + folder + " with " + rows + " rows, renderingEnabled " + graphics.get("renderingEnabled")
                    + ", chunkRenderDistance " + graphics.get("chunkRenderDistance"));
            leave();
            radius(FarViewSettings.DEFAULT_RADIUS);
            line("F15", pass && off, String.join("; ", seen));
        }

        /** No API override of DH's rendering switch, radius or resolution. */
        static boolean noOverride(Map<String, String> graphics) {
            return graphics.get("renderingEnabled").contains("api=null ") && graphics.get("chunkRenderDistance").contains("api=null ")
                    && graphics.get("maxHorizontalResolution").contains("api=null ");
        }

        /** The level's library is the empty one of Synaxis connections without a far view. */
        static boolean pinnedOff(@Nullable String folder) {
            return folder != null && folder.replace('\\', '/').endsWith("synaxis/farview/dh/" + FarViewClient.NO_FAR_VIEW);
        }

        /** A server that announces nothing: DH read only with rendering off, both undone on disconnect. */
        void none() {
            if (!connect()) {
                line("F16", false, "not in the world");
                return;
            }
            context.waitTicks(100);
            final Boolean readOnly = query(DhTestHook::readOnlyNow);
            final Map<String, String> graphics = graphics();
            final String folder = query(DhTestHook::saveFolder);
            walk(200);
            final long rows = count("DetailLevel >= 0");
            final FarViewConnection c = FarViewClient.current();
            leave();
            final Map<String, String> after = graphics();
            line("F16", c == null && Boolean.TRUE.equals(readOnly) && noOverride(graphics) && pinnedOff(folder) && rows == 0
                            && noOverride(after),
                    "announcement " + (c == null ? "none" : "one") + ", getReadOnly " + readOnly + ", library " + folder + " with "
                            + rows + " rows after a 200 block walk, renderingEnabled " + graphics.get("renderingEnabled")
                            + ", chunkRenderDistance " + graphics.get("chunkRenderDistance") + "; after the disconnect "
                            + after.get("renderingEnabled"));
        }

        /** No DH: the mod loads, the world is entered, nothing is downloaded. */
        void absent() {
            final int access = accessLog().size();
            final int logFrom = log().size();
            final boolean in = connect();
            context.waitTicks(100);
            final int accessAfter = accessLog().size();
            final List<String> log = logSince(logFrom);
            final List<String> errors = log.stream().filter(line -> (line.contains("Exception") || line.contains("ERROR"))
                    && line.toLowerCase(Locale.ROOT).contains("farview")).toList();
            line("F17", !dh && in && accessAfter == access && FarViewClient.gate() == FarViewClient.Gate.ABSENT && errors.isEmpty(),
                    "Distant Horizons loaded " + dh + ", in the world " + in + ", gate " + FarViewClient.gate() + ", access.log "
                            + access + " -> " + accessAfter + " lines, far view errors " + errors + ", announcement line "
                            + (find(log, "announcement received") != null));
        }

        /** The fixture's break-sha: three requests for the broken pack, then no far view and no popup. */
        void breakSha() {
            clearCache();
            final int access = accessLog().size();
            final int logFrom = log().size();
            final boolean in = connect();
            final boolean failed = until(() -> {
                final FarViewConnection c = connection();
                return c != null && c.failure() != null;
            }, 20 * 120);
            final FarViewConnection c = Objects.requireNonNull(connection());
            final FarViewAnnouncement.Tile whole = c.announcement().tiles().stream()
                    .filter(tile -> tile.layer() == FarViewAnnouncement.LAYER_FULL).findFirst().orElseThrow();
            final String hex = FarViewConnection.hex(whole);
            final List<String> requests = accessLog().subList(access, accessLog().size()).stream()
                    .filter(line -> line.contains(hex)).toList();
            final String warn = find(logSince(logFrom), "Far view pack " + hex.substring(0, 12) + " failed after 3 attempts");
            final boolean leftNothing = !Files.exists(cache().pack(hex)) && !Files.exists(cache().part(hex))
                    && cache().marker(c.viewKey()).isEmpty();
            final boolean playing = context.computeOnClient(client -> inWorld(client) && client.gui.screen() == null);
            line("F18", in && failed && requests.size() == 3 && warn != null && leftNothing && playing,
                    requests.size() + " requests for " + hex.substring(0, 12) + " " + requests + "; " + (warn == null ? "no WARN"
                            : warn.substring(warn.indexOf("Far view pack"))) + "; pack, .part and marker left " + !leftNothing
                            + "; in the world without a screen " + playing);
        }

        /** The fixture's half: the first response cut in half, the second asked for the rest with Range. */
        void half() {
            clearCache();
            final int access = accessLog().size();
            final boolean in = connect();
            final boolean ready = in && ready();
            final List<String> requests = accessLog().subList(access, accessLog().size());
            final String cut = requests.stream().filter(line -> line.endsWith(" cut")).findFirst().orElse(null);
            final String path = cut == null ? "" : cut.split(" ")[1];
            final String resumed = requests.stream().filter(line -> line.contains(path) && line.contains(" bytes=")
                    && line.contains(" 206")).findFirst().orElse(null);
            line("F19", ready && cut != null && resumed != null, "cut " + cut + "; resumed " + resumed + "; ready " + ready);
        }

        // ---- V2a, V3a, V6a, V8a: readings, recorded

        /**
         * V2a ({@code v2}) and V2b ({@code bake-timing}): the bake line's readings from an empty cache, the objects'
         * sizes, and the LOD's brightness against the near vanilla ground. Only {@code v2} holds the bake for a shot of
         * the empty LOD_VIEW, which it then tells the drawn one apart from; {@code bake-timing}, the 30 s judgement of
         * the real machines, never waits on the test.
         */
        void timing(String mode) {
            clearCache();
            radius(FarViewSettings.DEFAULT_RADIUS);
            final boolean hold = mode.equals("v2");
            final String id = hold ? "F23" : "F28";
            final Map.Entry<Path, Long> held = hold ? heldBake(mode) : null;
            if (hold ? held == null : !connect()) {
                line(id, false, "not in the world");
                return;
            }
            final Path emptyShot = held == null ? null : held.getKey();
            final boolean ready = ready();
            final FarViewConnection c = Objects.requireNonNull(connection());
            final FarViewConnection.Timings t = c.timings();
            long whole = 0, coarse = 0;
            for (FarViewAnnouncement.Tile tile : c.announcement().tiles()) {
                if (tile.layer() == FarViewAnnouncement.LAYER_FULL) whole += tile.bytes();
                else coarse += tile.bytes();
            }
            final Path lod = drawn(mode + "-lod", lodView(), emptyShot, 2400);
            final Path near = shot(mode + "-near", at(spawn.x, spawn.y + 25, spawn.z, -90f, 60f), 40);
            final BufferedImage lodImage = Shots.read(lod), nearImage = Shots.read(near);
            final String drawnShare = emptyShot == null ? "not judged"
                    : Shots.percent(Shots.changed(Shots.read(emptyShot), lodImage, Shots.Box.whole(lodImage))) + " drawn";
            final double lodLuma = Shots.luma(Shots.mean(lodImage, Shots.Box.rows(lodImage, 360, 720)));
            final double nearLuma = Shots.luma(Shots.mean(nearImage, Shots.Box.centre(nearImage, 300)));
            line(id, ready && t != null, String.format(Locale.ROOT, "%s: visible_ms %d (the test held the bake %d ms), "
                            + "ms %d, write_ms %d, db_bytes %d, sources %d, from the level load %d ms, download %d ms; whole pack %d B, "
                            + "coarse packs %d B; LOD_VIEW %s, lower half luma %.1f, near ground luma %.1f, ratio %.2f",
                    mode, t == null ? -1 : t.visibleMs(), held == null ? 0 : held.getValue(), t == null ? -1 : t.ms(),
                    t == null ? -1 : t.writeMs(),
                    t == null ? -1 : t.dbBytes(), t == null ? -1 : t.sources(), t == null ? -1 : t.fromLevelMs(), c.downloadMillis(),
                    whole, coarse, drawnShare, lodLuma, nearLuma, nearLuma == 0 ? 0 : lodLuma / nearLuma));
        }

        /** V3a: the far and LOD views with the shader pack off and on (DH + Sodium + Iris + Complementary). */
        void shaders() {
            if (!iris) {
                line("F20", false, "Iris is not loaded: run with SYNAXIS_FARVIEW_MODS=dh-iris");
                return;
            }
            clearCache();
            radius(FarViewSettings.DEFAULT_RADIUS);
            final Map.Entry<Path, Long> held = heldBake("v3");
            if (held == null || !ready()) {
                line("F20", false, "no ready far view");
                return;
            }
            final String pack = installShaderPack();
            final List<String> seen = new ArrayList<>();
            final Map<String, Path> off = new LinkedHashMap<>();
            setShaders(false);
            context.waitTicks(100);
            off.put("lod", drawn("v3-lod-off", lodView(), held.getKey(), 2400));
            off.put("far", stable("v3-far-off", farView(), 1200));
            setShaders(true);
            context.waitTicks(300);
            final Path farOn = stable("v3-far-on", farView(), 2400);
            final Path lodOn = shot("v3-lod-on", lodView(), 100);
            final Object inUse = irisCall("isShaderPackInUse");
            for (Map.Entry<String, Path> entry : Map.of("far", farOn, "lod", lodOn).entrySet()) {
                final BufferedImage a = Shots.read(off.get(entry.getKey())), b = Shots.read(entry.getValue());
                seen.add(entry.getKey() + " changed " + Shots.percent(Shots.changed(a, b, Shots.Box.whole(a))) + ", mean off "
                        + Shots.hex(Shots.mean(a, Shots.Box.whole(a))) + " on " + Shots.hex(Shots.mean(b, Shots.Box.whole(b)))
                        + ", lower half mean off " + Shots.hex(Shots.mean(a, Shots.Box.rows(a, 360, 720))) + " on "
                        + Shots.hex(Shots.mean(b, Shots.Box.rows(b, 360, 720))));
            }
            setShaders(false);
            line("F20", Boolean.TRUE.equals(inUse), "pack " + pack + ", in use " + inUse + "; " + String.join("; ", seen));
        }

        String installShaderPack() {
            final String packs = env("SYNAXIS_DH_SHADERPACKS", "/dh/shaderpacks");
            final Path irisPacks = (Path) reflect(null, "net.irisshaders.iris.Iris", "getShaderpacksDirectory", new Class<?>[0]);
            String pack = "none";
            try (Stream<Path> files = Files.list(Path.of(packs))) {
                for (Path file : files.toList()) {
                    Files.createDirectories(irisPacks);
                    Files.copy(file, irisPacks.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    pack = file.getFileName().toString();
                }
            } catch (IOException e) {
                note("no shader pack copied: " + e);
            }
            final Object config = reflect(null, "net.irisshaders.iris.Iris", "getIrisConfig", new Class<?>[0]);
            reflect(config, "net.irisshaders.iris.config.IrisConfig", "setShaderPackName", new Class<?>[]{String.class}, pack);
            return pack;
        }

        void setShaders(boolean enabled) {
            final Object api = reflect(null, "net.irisshaders.iris.api.v0.IrisApi", "getInstance", new Class<?>[0]);
            final Object config = reflect(api, "net.irisshaders.iris.api.v0.IrisApi", "getConfig", new Class<?>[0]);
            context.runOnClient(_ -> reflect(config, "net.irisshaders.iris.api.v0.IrisApiConfig", "setShadersEnabledAndApply",
                    new Class<?>[]{boolean.class}, enabled));
        }

        @Nullable Object irisCall(String method) {
            final Object api = reflect(null, "net.irisshaders.iris.api.v0.IrisApi", "getInstance", new Class<?>[0]);
            return reflect(api, "net.irisshaders.iris.api.v0.IrisApi", method, new Class<?>[0]);
        }

        static @Nullable Object reflect(@Nullable Object target, String owner, String method, Class<?>[] types, Object... args) {
            try {
                return Class.forName(owner).getMethod(method, types).invoke(target, args);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException(owner + "#" + method, e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(owner + "#" + method, e);
            }
        }

        /**
         * V6a: the perspective RTS camera 300 blocks from the player, the hole around it at two overdraw settings. A
         * hole shows the colour of the empty frame, so the share of the lower half within {@link Shots#THRESHOLD} of
         * that colour is the hole; DH is never switched off for a reference (see DhSession).
         */
        void rtsHoles() {
            clearCache();
            radius(FarViewSettings.DEFAULT_RADIUS);
            final Map.Entry<Path, Long> held = heldBake("v6");
            if (held == null || !ready()) {
                line("F21", false, "no ready far view");
                return;
            }
            // DH's render buffers first, judged where nothing but LOD is in view
            drawn("v6-lod", lodView(), held.getKey(), 2400);
            final BufferedImage empty = Shots.read(held.getKey());
            final int nothing = Shots.mean(empty, Shots.Box.rows(empty, SHOT_HEIGHT / 2, SHOT_HEIGHT));
            final List<String> seen = new ArrayList<>();
            final double x = spawn.x + 300;
            final int ground = groundAt((int) x, (int) spawn.z);
            for (int height : new int[]{64, 100}) {
                final SpikeCamera.Settings camera = at(x, ground + height, spawn.z, -90f, 45f);
                for (Float overdraw : new Float[]{null, 0f}) {
                    context.runOnClient(_ -> DhTestHook.overdrawPreventionRadius(overdraw));
                    final String label = "v6-h" + height + "-overdraw-" + (overdraw == null ? "default" : "0");
                    context.waitTicks(200);
                    final BufferedImage b = Shots.read(stable(label, camera, 800));
                    seen.add(label + ": lower half empty " + Shots.percent(Shots.near(b, Shots.Box.rows(b, SHOT_HEIGHT / 2, SHOT_HEIGHT),
                            nothing)) + ", whole frame " + Shots.percent(Shots.near(b, Shots.Box.whole(b), nothing)));
                }
                context.runOnClient(_ -> DhTestHook.overdrawPreventionRadius(null));
            }
            line("F21", true, "eye 300 blocks east of the player over ground y " + ground + ", pitch 45, empty colour "
                    + Shots.hex(nothing) + "; " + String.join("; ", seen));
        }

        /** The absolute y of the top of a column as the cache's whole pack holds it, the spawn's y without one. */
        int groundAt(int x, int z) {
            final FarViewConnection c = connection();
            if (c == null) return (int) spawn.y;
            for (FarViewAnnouncement.Tile tile : c.announcement().tiles()) {
                if (tile.layer() != FarViewAnnouncement.LAYER_FULL || tile.tileX() != Math.floorDiv(x, 4096)
                        || tile.tileZ() != Math.floorDiv(z, 4096)) continue;
                final FarViewPack.Reader reader = reader(tile);
                final int b = reader.header().baseLevel();
                final int sx = (x - tile.tileX() * 4096) >> b, sz = (z - tile.tileZ() * 4096) >> b;
                for (FarViewPack.Block block : reader.blocks(FarViewPack.Layer.FINE)) {
                    if (block.bx() != sx / 64 || block.bz() != sz / 64) continue;
                    try {
                        return reader.header().minY() + Math.max(0, reader.samples(block).get((sz % 64) * 64 + sx % 64).top());
                    } catch (FarViewPack.Invalid e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
            return (int) spawn.y;
        }

        /** V8a: the heap DH holds at 4,096 and 2,048 against none, and how long from the level load to the far view. */
        void memory() {
            final List<String> seen = new ArrayList<>();
            final Map<Integer, Long> heap = new LinkedHashMap<>();
            for (int radius : new int[]{FarViewSettings.OFF, FarViewSettings.DEFAULT_RADIUS, FarViewSettings.LOW_RADIUS}) {
                clearCache();
                radius(radius);
                if (radius == FarViewSettings.OFF) {
                    if (!connect()) {
                        line("F22", false, "not in the world at 0");
                        return;
                    }
                    context.waitTicks(200);
                } else {
                    final Map.Entry<Path, Long> held = heldBake("v8-" + radius);
                    if (held == null) {
                        line("F22", false, "not in the world at " + radius);
                        return;
                    }
                    final boolean ready = ready();
                    final FarViewConnection c = Objects.requireNonNull(connection());
                    final BufferedImage empty = Shots.read(held.getKey());
                    long drawnMs = -1;
                    BufferedImage before = null;
                    Path last = shot("v8-" + radius + "-lod-0", lodView(), 20);
                    for (int i = 1; i < 120; i++) {
                        final Path next = shot("v8-" + radius + "-lod-" + i, lodView(), 20);
                        final BufferedImage a = Shots.read(last), b = Shots.read(next);
                        last = next;
                        final boolean settled = Shots.changed(a, b, Shots.Box.whole(a)) < 0.001
                                || before != null && Shots.changed(before, b, Shots.Box.whole(b)) < 0.001;
                        before = a;
                        if (Shots.changed(empty, b, Shots.Box.whole(b)) > 0.01 && settled) {
                            drawnMs = FarViewConnection.millisSince(c.levelLoadedNanos());
                            break;
                        }
                    }
                    final FarViewConnection.Timings t = c.timings();
                    seen.add(radius + ": ready " + ready + ", level load to bake line " + (t == null ? -1 : t.fromLevelMs())
                            + " ms, to LOD_VIEW drawn and stable " + drawnMs + " ms (the bake held " + held.getValue()
                            + " ms for the empty shot)");
                }
                System.gc();
                System.gc();
                final Runtime runtime = Runtime.getRuntime();
                heap.put(radius, runtime.totalMemory() - runtime.freeMemory());
                leave();
            }
            final long none = heap.get(FarViewSettings.OFF);
            for (Map.Entry<Integer, Long> entry : heap.entrySet()) {
                seen.add("heap after two GCs at " + entry.getKey() + ": " + entry.getValue() / (1024 * 1024) + " MiB, "
                        + (entry.getValue() - none) / (1024 * 1024) + " MiB over 0");
            }
            radius(FarViewSettings.DEFAULT_RADIUS);
            line("F22", true, String.join("; ", seen));
        }

        /**
         * Why {@code renderingEnabled} is never overridden (DhSession): DH 3.3.2 writes the override into the player's
         * {@code DistantHorizons.toml}, so after the override false is cleared DH stays off, in the same world and after a
         * reconnect; an override true and its clearing put the file back. Passes while DH behaves so; run it last, it
         * changes DH's config file for its own duration.
         */
        void toggle() {
            clearCache();
            radius(FarViewSettings.DEFAULT_RADIUS);
            final Map.Entry<Path, Long> held = heldBake("toggle");
            if (held == null || !ready()) {
                line("F27", false, "no ready far view");
                return;
            }
            final BufferedImage empty = Shots.read(held.getKey());
            final BufferedImage drawnFirst = Shots.read(drawn("toggle-first", lodView(), held.getKey(), 2400));
            final List<String> seen = new ArrayList<>();
            final double first = Shots.changed(empty, drawnFirst, Shots.Box.whole(drawnFirst));
            seen.add("drawn " + Shots.percent(first) + ", DistantHorizons.toml " + dhToml());
            context.runOnClient(_ -> DhTestHook.renderingEnabled(false));
            context.waitTicks(100);
            context.runOnClient(_ -> DhTestHook.renderingEnabled(null));
            final BufferedImage cleared = Shots.read(drawn("toggle-cleared", lodView(), held.getKey(), 1200));
            final double clearedShare = Shots.changed(empty, cleared, Shots.Box.whole(cleared));
            final String clearedToml = dhToml();
            seen.add("override false, then cleared: drawn " + Shots.percent(clearedShare) + ", " + graphics().get("renderingEnabled")
                    + ", DistantHorizons.toml " + clearedToml);
            leave();
            if (!connect()) {
                line("F27", false, "not in the world again");
                return;
            }
            ready();
            final BufferedImage again = Shots.read(drawn("toggle-reconnect", lodView(), held.getKey(), 1200));
            final double againShare = Shots.changed(empty, again, Shots.Box.whole(again));
            seen.add("after a reconnect: drawn " + Shots.percent(againShare));
            context.runOnClient(_ -> DhTestHook.renderingEnabled(true));
            context.waitTicks(20);
            context.runOnClient(_ -> DhTestHook.renderingEnabled(null));
            final BufferedImage restored = Shots.read(drawn("toggle-restored", lodView(), held.getKey(), 1200));
            final double restoredShare = Shots.changed(empty, restored, Shots.Box.whole(restored));
            final String restoredToml = dhToml();
            seen.add("override true, then cleared: drawn " + Shots.percent(restoredShare) + ", DistantHorizons.toml " + restoredToml);
            line("F27", first > 0.01 && clearedShare < 0.01 && againShare < 0.01 && clearedToml.contains("DISABLED")
                            && restoredShare > 0.01 && !restoredToml.contains("DISABLED"),
                    "DH 3.3.2 keeps an override of renderingEnabled in the player's config: " + String.join("; ", seen));
        }

        /** The rendering switches of DH's own config file, which the player owns. */
        String dhToml() {
            try {
                return Files.readAllLines(FabricLoader.getInstance().getConfigDir().resolve("DistantHorizons.toml")).stream()
                        .map(String::trim).filter(l -> l.startsWith("enableRendering") || l.startsWith("rendererMode")
                                || l.startsWith("quickEnableRendering")).toList().toString();
            } catch (IOException e) {
                return "unreadable: " + e;
            }
        }

        // ---- Real machine modes (§26 M3-G 真机验收): the user runs these, the container only smoke tests them

        /** V3b: the four camera spots of /expect.json, shader pack off and (with Iris) on, then 10 s along the vanilla edge. */
        void realShots() {
            clearCache();
            radius(FarViewSettings.DEFAULT_RADIUS);
            final Map.Entry<Path, Long> held = heldBake("v3b");
            if (held == null || !ready()) {
                line("F24", false, "no ready far view");
                return;
            }
            drawn("v3b-lod", lodView(), held.getKey(), 2400);
            if (iris) installShaderPack();
            final List<String> taken = new ArrayList<>();
            for (boolean on : iris ? new boolean[]{false, true} : new boolean[]{false}) {
                if (iris) {
                    setShaders(on);
                    context.waitTicks(on ? 300 : 100);
                }
                for (String name : List.of("plain", "lake", "ridge", "edge-outward")) {
                    if (!expect().getAsJsonObject("views").has(name)) continue;
                    taken.add(stable("v3b-" + name + (on ? "-shader" : "-vanilla"), view(name), 1200).getFileName().toString());
                }
            }
            // 10 s along the vanilla view's edge, 192 blocks from the player, for the user's own recording
            for (int tick = 0; tick < 200; tick++) {
                final double angle = tick * Math.PI / 100;
                final SpikeCamera.Settings camera = at(spawn.x + 192 * Math.cos(angle), spawn.y + 30, spawn.z + 192 * Math.sin(angle),
                        (float) Math.toDegrees(angle) + 90f, 10f);
                context.runOnClient(_ -> SpikeCamera.set(camera));
                context.waitTick();
            }
            line("F24", !taken.isEmpty(), "shots " + taken);
        }

        /**
         * V6b: the perspective RTS camera at eye 64 and 100 above the ground, and one ortho shot for the record. The
         * sun stands where the fixture's clock has it: the client of 26.2 takes the day time from the server's clock,
         * so the 40° shot needs the fixture to set its time.
         */
        void realRtsShots() {
            clearCache();
            radius(FarViewSettings.DEFAULT_RADIUS);
            final Map.Entry<Path, Long> held = heldBake("v6b");
            if (held == null || !ready()) {
                line("F25", false, "no ready far view");
                return;
            }
            drawn("v6b-lod", lodView(), held.getKey(), 2400);
            final List<String> taken = new ArrayList<>();
            final int ground = groundAt((int) spawn.x, (int) spawn.z);
            for (int height : new int[]{64, 100}) {
                taken.add(stable("v6b-h" + height, at(spawn.x, ground + height, spawn.z - 60, 0f, 55f), 800).getFileName().toString());
            }
            context.runOnClient(_ -> SpikeCamera.set(new SpikeCamera.Settings(SpikeCamera.Mode.ORTHO,
                    new Vec3(spawn.x, ground, spawn.z), -90f, 60f, 64f, 96f)));
            context.waitTicks(100);
            taken.add(context.takeScreenshot(TestScreenshotOptions.of("farview-v6b-ortho").withSize(SHOT_WIDTH, SHOT_HEIGHT))
                    .getFileName().toString());
            final long dayTime = context.computeOnClient(client -> client.level.getOverworldClockTime());
            line("F25", true, "day time " + dayTime + ", shots " + taken);
        }

        /**
         * V8b: 60 s along a fixed route at 4,096 and then without a far view, frame times to farview-bench.csv, at the
         * vanilla render distance 12 of the bench and, with Iris, the shader pack's default profile.
         */
        void bench() {
            final List<String> csv = new ArrayList<>();
            csv.add("radius,frame,ms");
            final List<String> seen = new ArrayList<>();
            for (int radius : new int[]{FarViewSettings.DEFAULT_RADIUS, FarViewSettings.OFF}) {
                radius(radius);
                if (radius == FarViewSettings.OFF) {
                    if (!connect()) {
                        line("F26", false, "not in the world at 0");
                        return;
                    }
                    context.waitTicks(200);
                } else {
                    clearCache();
                    final Map.Entry<Path, Long> held = heldBake("v8b-" + radius);
                    if (held == null || !ready()) {
                        line("F26", false, "no far view at " + radius);
                        return;
                    }
                    drawn("v8b-" + radius + "-lod", lodView(), held.getKey(), 2400);
                }
                context.runOnClient(client -> client.options.renderDistance().set(12));
                if (iris) {
                    installShaderPack();
                    setShaders(true);
                }
                context.waitTicks(300);
                FrameTimes.start();
                for (int tick = 0; tick < 1200; tick++) {
                    final double along = tick / 1200.0;
                    final SpikeCamera.Settings camera = at(spawn.x - 1500 + 3000 * along, spawn.y + 80, spawn.z, -90f, 15f);
                    context.runOnClient(_ -> SpikeCamera.set(camera));
                    context.waitTick();
                }
                final List<Long> times = FrameTimes.stop();
                for (int i = 0; i < times.size(); i++) csv.add(radius + "," + i + "," + times.get(i) / 1e6);
                final List<Long> sorted = new ArrayList<>(times);
                sorted.sort(Comparator.naturalOrder());
                final double mean = times.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
                final double p99 = sorted.isEmpty() ? 0 : sorted.get((int) Math.floor(0.99 * (sorted.size() - 1))) / 1e6;
                seen.add(String.format(Locale.ROOT, "%d: %d frames, mean %.2f ms, p99 %.2f ms, P1%% %.1f FPS", radius, times.size(),
                        mean, p99, p99 == 0 ? 0 : 1000 / p99));
                leave();
            }
            try {
                Files.write(gameDir.resolve("synaxis-gametest").resolve("farview-bench.csv"), csv, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            radius(FarViewSettings.DEFAULT_RADIUS);
            line("F26", true, String.join("; ", seen));
        }
    }
}
