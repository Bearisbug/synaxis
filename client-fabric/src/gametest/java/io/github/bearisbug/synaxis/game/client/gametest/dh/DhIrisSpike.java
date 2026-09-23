package io.github.bearisbug.synaxis.game.client.gametest.dh;

import io.github.bearisbug.synaxis.game.client.gametest.camera.SpikeCamera;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Distant Horizons / Iris spike (docs/research/client-spikes-2026-09/dh-iris/README.md), run
 * only when SYNAXIS_SPIKES names {@code dh}; dh-iris/run.sh hands the third-party jars to Fabric
 * Loader through {@code -Dfabric.addMods}.
 * <p>
 * ① A block of synthetic chunks east of the map, outside the server's view distance, goes to DH
 * through {@code IDhApiTerrainDataRepo#overwriteChunkDataAsync}; the report says whether DH stored
 * data for it and how many pixels of the far view changed. ② With Iris installed, the same view with
 * the shader pack off and on. ③ The spike camera's ortho and perspective shapes with all of it
 * loaded. DH and Iris are reached by reflection, so the gametest source set compiles without them.
 */
public final class DhIrisSpike {

    private static final String DH = "com.seibel.distanthorizons.api.";

    /** Injected chunks: x 544..927, z 64..447, east of the rts-512 map and past view distance 10 from (256, 256). */
    private static final int CHUNK_X0 = 34;
    private static final int CHUNK_X1 = 57;
    private static final int CHUNK_Z0 = 4;
    private static final int CHUNK_Z1 = 27;

    /** The far view: eye near (302, 51, 256) looking east, 6 degrees down, over the injected block. */
    private static final SpikeCamera.Settings FAR_VIEW =
            new SpikeCamera.Settings(SpikeCamera.Mode.PERSPECTIVE, new Vec3(600, 20, 256), -90f, 6f, 300f, 96f);

    /** Inside the injected block: eye near (591, 88, 256) looking east 25 degrees down, nothing but LODs in frame. */
    private static final SpikeCamera.Settings LOD_VIEW =
            new SpikeCamera.Settings(SpikeCamera.Mode.PERSPECTIVE, new Vec3(736, 20, 256), -90f, 25f, 160f, 96f);

    private static final int SHOT_WIDTH = 1280;
    private static final int SHOT_HEIGHT = 720;

    private DhIrisSpike() {}

    public static void run(ClientGameTestContext context, List<String> report) {
        for (String id : List.of("distanthorizons", "iris", "sodium")) {
            report.add("DH mod " + id + " " + FabricLoader.getInstance().getModContainer(id)
                    .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("absent"));
        }
        final boolean dh = FabricLoader.getInstance().isModLoaded("distanthorizons");
        final boolean iris = FabricLoader.getInstance().isModLoaded("iris");
        context.runOnClient(client -> {
            client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
        });

        if (dh) injectAndCompare(context, report);
        if (iris) shaderOnOff(context, report);
        cameraShapes(context, report);
        context.runOnClient(_ -> SpikeCamera.set(null));
    }

    /** ①: before shot, inject, wait for DH to hold the data, after shot, pixel difference. */
    private static void injectAndCompare(ClientGameTestContext context, List<String> report) {
        report.add("DH api " + call(null, DH + "DhApi", "getModVersion") + " api_major "
                + call(null, DH + "DhApi", "getApiMajorVersion") + " network_protocol "
                + call(null, DH + "DhApi", "getNetworkProtocolVersion"));
        final int waitedForWorld = waitUntil(context, 20 * 60, () -> Boolean.TRUE.equals(
                call(delayed("worldProxy"), DH + "interfaces.world.IDhApiWorldProxy", "worldLoaded")));
        final Object level = levelWrapper();
        report.add("DH world_loaded after " + waitedForWorld + " ticks, level " + (level == null ? "none"
                : call(level, DH + "interfaces.world.IDhApiLevelWrapper", "getDhIdentifier")));
        if (level == null) return;

        context.runOnClient(_ -> SpikeCamera.set(FAR_VIEW));
        context.waitTicks(60);
        final Path before = shot(context, "far-before");
        context.runOnClient(_ -> SpikeCamera.set(LOD_VIEW));
        context.waitTicks(20);
        final Path lodBefore = shot(context, "lod-before");
        report.add("DH far view before injection: column at 736,256 " + column(context, level, 736, 256));

        final long start = System.nanoTime();
        final List<String> failures = new ArrayList<>();
        int injected = 0;
        final ClientLevel clientLevel = context.computeOnClient(client -> client.level);
        for (int cx = CHUNK_X0; cx <= CHUNK_X1; cx++) {
            for (int cz = CHUNK_Z0; cz <= CHUNK_Z1; cz++) {
                final LevelChunk chunk = synthetic(clientLevel, cx, cz);
                final Object result = invoke(delayed("terrainRepo"), DH + "interfaces.data.IDhApiTerrainDataRepo",
                        "overwriteChunkDataAsync", new Class<?>[] {type(DH + "interfaces.world.IDhApiLevelWrapper"), Object[].class},
                        level, new Object[] {chunk, clientLevel});
                if (Boolean.TRUE.equals(field(result, "success"))) {
                    injected++;
                } else if (failures.size() < 3) {
                    failures.add(cx + "," + cz + ": " + field(result, "message"));
                }
            }
        }
        report.add(String.format(Locale.ROOT, "DH injected %d of %d chunks in %.0f ms, failures %s", injected,
                (CHUNK_X1 - CHUNK_X0 + 1) * (CHUNK_Z1 - CHUNK_Z0 + 1), (System.nanoTime() - start) / 1e6, failures));

        final int stored = waitUntil(context, 20 * 120, () -> column(context, level, 736, 256).startsWith("top"));
        report.add("DH column data at 736,256 after " + stored + " ticks: " + column(context, level, 736, 256)
                + "; at 560,80 " + column(context, level, 560, 80) + "; control 1200,256 " + column(context, level, 1200, 256));
        context.runOnClient(_ -> SpikeCamera.set(FAR_VIEW));
        for (int wait : new int[] {100, 400}) {
            context.waitTicks(wait);
            final Path after = shot(context, "far-after-" + wait);
            report.add("DH far view " + wait + " ticks after the data: " + diff(before, after));
        }
        context.runOnClient(_ -> SpikeCamera.set(LOD_VIEW));
        context.waitTicks(20);
        report.add("DH view inside the injected block, after vs before: " + diff(lodBefore, shot(context, "lod-after")));
    }

    /**
     * ②: the far view and a view of nothing but LODs, with the Iris shader pack off, then on. The pack comes from
     * SYNAXIS_DH_SHADERPACKS: Loom clears the run directory before the game starts, so the pack is
     * copied into Iris' shaderpacks directory here and selected through Iris' own config.
     */
    private static void shaderOnOff(ClientGameTestContext context, List<String> report) {
        final String api = "net.irisshaders.iris.api.v0.";
        final String packs = System.getenv("SYNAXIS_DH_SHADERPACKS");
        final Path irisPacks = (Path) call(null, "net.irisshaders.iris.Iris", "getShaderpacksDirectory");
        String pack = "none";
        try (var files = Files.list(Path.of(packs == null ? "/nonexistent" : packs))) {
            for (Path file : files.toList()) {
                Files.createDirectories(irisPacks);
                Files.copy(file, irisPacks.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                pack = file.getFileName().toString();
            }
        } catch (IOException e) {
            report.add("IRIS no shader pack copied: " + e);
        }
        final Object irisConfig = call(null, "net.irisshaders.iris.Iris", "getIrisConfig");
        invoke(irisConfig, "net.irisshaders.iris.config.IrisConfig", "setShaderPackName", new Class<?>[] {String.class}, pack);
        report.add("IRIS pack " + pack);
        final Object iris = call(null, api + "IrisApi", "getInstance");
        final Object config = call(iris, api + "IrisApi", "getConfig");
        final List<Path> off = new ArrayList<>();
        final List<Path> on = new ArrayList<>();
        // Three passes: shaders off, the pack with its defaults, then the pack with its atmospheric and
        // border fog options off, compared against both earlier passes to tell whether those options
        // cause the haze over the LODs.
        for (String state : List.of("off", "on", "on-lowfog")) {
            final boolean enabled = !state.equals("off");
            if (state.equals("on-lowfog")) {
                try {
                    Files.writeString(irisPacks.resolve(pack + ".txt"), "ATM_FOG_ALTITUDE=0\nATM_FOG_DISTANCE=300\nBORDER_FOG=false\n");
                } catch (IOException e) {
                    report.add("IRIS could not write pack options: " + e);
                }
                context.runOnClient(_ -> invoke(config, api + "IrisApiConfig", "setShadersEnabledAndApply",
                        new Class<?>[] {boolean.class}, false));
                context.waitTicks(5);
            }
            context.runOnClient(_ -> invoke(config, api + "IrisApiConfig", "setShadersEnabledAndApply",
                    new Class<?>[] {boolean.class}, enabled));
            // DH drops its LOD render data when Iris switches pipelines and rebuilds it over the next
            // ticks; at about 2.5 frames a second under llvmpipe with the pack on, 300 ticks is two minutes.
            context.waitTicks(enabled ? 300 : 40);
            final List<SpikeCamera.Settings> views = List.of(FAR_VIEW, LOD_VIEW);
            for (int i = 0; i < views.size(); i++) {
                final SpikeCamera.Settings view = views.get(i);
                context.runOnClient(_ -> SpikeCamera.set(view));
                context.waitTicks(20);
                final String name = "iris-" + state + (i == 0 ? "-far" : "-lod");
                final Path shot = shot(context, name);
                report.add("IRIS " + name + " pack_in_use " + call(iris, api + "IrisApi", "isShaderPackInUse")
                        + (enabled ? " vs off: " + diff(off.get(i), shot) : "")
                        + (state.equals("on-lowfog") ? "; vs on: " + diff(on.get(i), shot) : ""));
                if (state.equals("off")) off.add(shot);
                if (state.equals("on")) on.add(shot);
            }
        }
    }

    /**
     * ③: the S2 shapes over the spawn, then over the injected block (anchor 600, 20, 256, which no
     * vanilla chunk covers, so anything drawn there is DH's), ortho at two frame heights and perspective.
     */
    private static void cameraShapes(ClientGameTestContext context, List<String> report) {
        final Vec3 feet = context.computeOnClient(client -> client.player.position());
        final Vec3 lod = new Vec3(600, 20, 256);
        final List<SpikeCamera.Settings> shapes = List.of(
                new SpikeCamera.Settings(SpikeCamera.Mode.ORTHO, feet, -90f, 60f, 64f, 96f),
                new SpikeCamera.Settings(SpikeCamera.Mode.PERSPECTIVE, feet, -90f, 60f, 64f, 96f),
                new SpikeCamera.Settings(SpikeCamera.Mode.ORTHO, lod, -90f, 60f, 64f, 96f),
                new SpikeCamera.Settings(SpikeCamera.Mode.ORTHO, lod, -90f, 60f, 64f, 480f),
                new SpikeCamera.Settings(SpikeCamera.Mode.PERSPECTIVE, lod, -90f, 60f, 64f, 96f),
                new SpikeCamera.Settings(SpikeCamera.Mode.PERSPECTIVE, lod, -90f, 60f, 400f, 96f));
        for (SpikeCamera.Settings shape : shapes) {
            context.runOnClient(_ -> SpikeCamera.set(shape));
            context.waitTicks(40);
            final String name = "cam-" + shape.label() + (shape.anchor().equals(lod) ? "-over-lod" : "");
            shot(context, name);
            report.add("CAM " + name + " rendered, eye " + context.computeOnClient(client -> client.gameRenderer.mainCamera().position()));
        }
    }

    /**
     * Ridges from 8 blocks above the world's floor up to 16 below its ceiling (the match world is 64
     * blocks tall), topped by grass, stone or snow by height, with a red concrete pillar 14 blocks
     * high every 64 blocks so the injected block cannot be mistaken for anything the server sent.
     */
    private static LevelChunk synthetic(ClientLevel level, int cx, int cz) {
        final LevelChunk chunk = new LevelChunk(level, new ChunkPos(cx, cz));
        final int floor = level.getMinY();
        final int span = level.getHeight() - 24;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                final int wx = cx * 16 + x;
                final int wz = cz * 16 + z;
                final double shape = shape(wx, wz);
                final int top = floor + 8 + (int) Math.round(span * shape);
                final boolean pillar = Math.floorMod(wx, 64) < 3 && Math.floorMod(wz, 64) < 3;
                final int columnTop = pillar ? Math.min(top + 14, level.getMaxY()) : top;
                for (int y = floor; y <= columnTop; y++) {
                    final BlockState state = pillar && y > top ? Blocks.CONCRETE.red().defaultBlockState()
                            : y < top ? (y < top - 3 ? Blocks.STONE.defaultBlockState() : Blocks.DIRT.defaultBlockState())
                            : shape >= 0.7 ? Blocks.SNOW_BLOCK.defaultBlockState()
                            : shape >= 0.45 ? Blocks.STONE.defaultBlockState()
                            : Blocks.GRASS_BLOCK.defaultBlockState();
                    chunk.getSection(chunk.getSectionIndex(y)).setBlockState(x, y & 15, z, state, false);
                }
            }
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
        chunk.setLightCorrect(true);
        return chunk;
    }

    /** 0..1: a ridge across x 544..928 rising to the middle, rippled along both axes. */
    static double shape(int wx, int wz) {
        final double across = Math.max(0, Math.sin(Math.PI * (wx - 544) / 384.0));
        final double ripple = 0.55 + 0.45 * Math.cos(wz / 41.0) * Math.cos(wx / 29.0);
        return across * ripple;
    }

    private static Path shot(ClientGameTestContext context, String name) {
        return context.takeScreenshot(TestScreenshotOptions.of("dh-" + name).withSize(SHOT_WIDTH, SHOT_HEIGHT));
    }

    /** Pixels whose largest channel difference exceeds 24, and their bounding box. */
    static String diff(Path a, Path b) {
        try {
            final BufferedImage x = ImageIO.read(a.toFile());
            final BufferedImage y = ImageIO.read(b.toFile());
            int changed = 0;
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = -1;
            int maxY = -1;
            for (int j = 0; j < x.getHeight(); j++) {
                for (int i = 0; i < x.getWidth(); i++) {
                    final int p = x.getRGB(i, j);
                    final int q = y.getRGB(i, j);
                    final int d = Math.max(Math.abs((p >> 16 & 255) - (q >> 16 & 255)),
                            Math.max(Math.abs((p >> 8 & 255) - (q >> 8 & 255)), Math.abs((p & 255) - (q & 255))));
                    if (d <= 24) continue;
                    changed++;
                    minX = Math.min(minX, i);
                    minY = Math.min(minY, j);
                    maxX = Math.max(maxX, i);
                    maxY = Math.max(maxY, j);
                }
            }
            final int total = x.getWidth() * x.getHeight();
            return String.format(Locale.ROOT, "%d of %d px changed (%.1f%%), box %d,%d..%d,%d", changed, total,
                    100.0 * changed / total, minX, minY, maxX, maxY);
        } catch (IOException e) {
            return "diff failed: " + e;
        }
    }

    /**
     * DH's column query blocks on a future its render-thread work completes, and it calls
     * Minecraft.getInstance(), which the gametest thread may not. So it runs on a thread of its own
     * while the test thread keeps the game ticking.
     */
    private static String column(ClientGameTestContext context, Object level, int x, int z) {
        final CompletableFuture<String> query = CompletableFuture.supplyAsync(() -> column(level, x, z),
                command -> Thread.ofPlatform().name("dh-spike-query").start(command));
        for (int waited = 0; !query.isDone() && waited < 600; waited += 5) context.waitTicks(5);
        return query.isDone() ? query.join() : "no answer within 600 ticks";
    }

    /** DH's column at a block position: the topmost non-air data point, or why there is none. */
    private static String column(Object level, int x, int z) {
        final Object result = invoke(delayed("terrainRepo"), DH + "interfaces.data.IDhApiTerrainDataRepo", "getColumnDataAtBlockPos",
                new Class<?>[] {type(DH + "interfaces.world.IDhApiLevelWrapper"), int.class, int.class,
                        type(DH + "interfaces.data.IDhApiTerrainDataCache")}, level, x, z,
                call(delayed("terrainRepo"), DH + "interfaces.data.IDhApiTerrainDataRepo", "createSoftCache"));
        if (!Boolean.TRUE.equals(field(result, "success"))) return "fail: " + field(result, "message");
        final Object[] points = (Object[]) field(result, "payload");
        if (points == null || points.length == 0) return "empty";
        int nonAir = 0;
        Object highest = null;
        for (Object point : points) {
            final Object block = field(point, "blockStateWrapper");
            if (Boolean.TRUE.equals(call(block, DH + "interfaces.block.IDhApiBlockStateWrapper", "isAir"))) continue;
            nonAir++;
            if (highest == null || (int) field(point, "topYBlockPos") > (int) field(highest, "topYBlockPos")) highest = point;
        }
        if (highest == null) return "air only (" + points.length + " points)";
        return "top y " + field(highest, "topYBlockPos") + " " + call(field(highest, "blockStateWrapper"),
                DH + "interfaces.block.IDhApiBlockStateWrapper", "getSerialString") + " sky " + field(highest, "skyLightLevel")
                + ", " + nonAir + " non-air of " + points.length + " points, detail " + field(highest, "detailLevel");
    }

    private static @Nullable Object levelWrapper() {
        final Object levels = call(delayed("worldProxy"), DH + "interfaces.world.IDhApiWorldProxy", "getAllLoadedLevelWrappers");
        for (Object level : (Iterable<?>) levels) return level;
        return null;
    }

    /** Ticks waited until the condition held, or -1 after {@code limit}. */
    private static int waitUntil(ClientGameTestContext context, int limit, BooleanSupplier condition) {
        for (int waited = 0; waited <= limit; waited += 20) {
            if (condition.getAsBoolean()) return waited;
            context.waitTicks(20);
        }
        return -1;
    }

    private static Object delayed(String name) {
        try {
            return type(DH + "DhApi$Delayed").getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static @Nullable Object call(@Nullable Object target, String owner, String method) {
        return invoke(target, owner, method, new Class<?>[0]);
    }

    private static @Nullable Object invoke(@Nullable Object target, String owner, String method, Class<?>[] types, @Nullable Object... args) {
        try {
            final Method m = type(owner).getMethod(method, types);
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(owner + "#" + method, e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(owner + "#" + method, e);
        }
    }

    private static @Nullable Object field(@Nullable Object target, String name) {
        if (target == null) return null;
        try {
            return target.getClass().getField(name).get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(name, e);
        }
    }
}
