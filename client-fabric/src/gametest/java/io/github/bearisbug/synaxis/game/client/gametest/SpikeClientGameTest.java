package io.github.bearisbug.synaxis.game.client.gametest;

import io.github.bearisbug.synaxis.game.client.gametest.camera.SpikeCamera;
import io.github.bearisbug.synaxis.game.client.gametest.ray.ScreenRay;
import io.github.bearisbug.synaxis.game.client.gametest.s5.InputSpike;
import io.github.bearisbug.synaxis.game.client.gametest.s5.TerrainSpike;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Spikes S0 to S5 of docs/research/client-spikes-2026-09/README.md, run by the real client.
 * <p>
 * S0: the mod and its nested :protocol are loaded. S1: the client gets through the configuration
 * phase handshake of a server-app in game mode and reaches the world. S2: the same scene through
 * the orthographic and the pitched perspective spike camera. S3: {@link EntityCullingSpike}. S4: the
 * world ray under the cursor, still and while the camera pans. S5: section visibility with the eye
 * high and outside the map, and the two mouse input paths. {@code chat}: the secure chat toast and
 * what one chat message brings back, see {@link #chat}. SYNAXIS_SPIKES picks among S2 to S5 and
 * {@code chat} ({@code all} for every one); unset, the spikes do not run at all and only
 * {@link RegressionClientGameTest} does. Findings are appended to {@code synaxis-gametest/report.txt}
 * in the run directory, after the regression's R lines; screenshots go to {@code screenshots/}.
 */
public final class SpikeClientGameTest implements FabricClientGameTest {

    private static final int CONNECT_TIMEOUT_TICKS = 20 * 60;

    @Override
    public void runTest(ClientGameTestContext context) {
        final String spikes = System.getenv("SYNAXIS_SPIKES");
        if (spikes == null || spikes.isBlank()) return;
        final List<String> report = new ArrayList<>();

        context.waitForScreen(TitleScreen.class);
        context.waitTicks(40); // the title screen fades its buttons in over the first second
        context.takeScreenshot("s0-title");
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            final String id = mod.getMetadata().getId();
            if (id.contains("synaxis")) report.add("S0 mod " + id + " " + mod.getMetadata().getVersion().getFriendlyString());
        }

        final String server = System.getenv("SYNAXIS_GAMETEST_SERVER");
        if (server == null || server.isBlank()) {
            report.add("S1 skipped: SYNAXIS_GAMETEST_SERVER not set");
            write(report);
            return;
        }
        // The gametest harness drops the render distance to 5 chunks, and server-app sends the
        // chunks of the distance the client joins with. 10 is server-app's own cap
        // (synaxis.chunk-view-distance), so the world ends where a player's would.
        context.runOnClient(client -> client.options.renderDistance().set(10));
        context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client,
                ServerAddress.parseString(server), new ServerData("synaxis", server, ServerData.Type.OTHER),
                false, null));
        context.waitFor(client -> client.level != null && client.player != null
                || client.gui.screen() instanceof DisconnectedScreen, CONNECT_TIMEOUT_TICKS);

        final String outcome = context.computeOnClient(client -> client.level != null
                ? "S1 in world at " + client.player.blockPosition()
                : "S1 disconnected: " + client.gui.screen().getNarrationMessage().getString());
        report.add(outcome);
        context.takeScreenshot("s1-after-connect");
        if (outcome.startsWith("S1 in world")) {
            if (enabled("chat")) chat(context, report);
            // A 20° downward look puts the ground in the picture. The terrain is lit by the light the
            // server computed; SYNAXIS_GAMETEST_NIGHT_VISION=true adds client-side night vision on top.
            context.runOnClient(client -> {
                if (Boolean.parseBoolean(System.getenv("SYNAXIS_GAMETEST_NIGHT_VISION"))) {
                    client.player.forceAddEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION), null);
                }
                client.player.setXRot(20f);
            });
            context.waitTicks(100);
            context.takeScreenshot("s1-in-world");
            if (enabled("s2")) cameraShapes(context, report);
            if (enabled("s3")) EntityCullingSpike.run(context, report);
            if (enabled("s4")) screenRay(context, report);
            if (enabled("s5")) sectionsAndInput(context, report);
            context.runOnClient(client -> client.disconnect(new TitleScreen(), false));
        } else {
            context.setScreen(TitleScreen::new);
        }
        context.waitForScreen(TitleScreen.class);
        write(report);
    }

    /**
     * Chat (REQ-PROD-009, docs/DESIGN-PROD.md ADR-PROD-009). The server does not enforce secure chat,
     * which makes a vanilla client put up the "Chat messages can't be verified" toast on joining; C1
     * says whether it is up. C2 sends one chat message and lists what came back: nothing unless a
     * server plugin relays it, and then a system message, never player chat. It runs right after S1
     * because the toast is gone 5 s after the join.
     */
    private static void chat(ClientGameTestContext context, List<String> report) {
        report.add(context.computeOnClient(client -> "C1 unsecure_server_toast=" + (client.gui.toastManager()
                .getToast(SystemToast.class, SystemToast.SystemToastId.UNSECURE_SERVER_WARNING) != null)));
        final List<String> system = new CopyOnWriteArrayList<>();
        final List<String> playerChat = new CopyOnWriteArrayList<>();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) system.add(message.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((message, _, _, _, _) -> playerChat.add(message.getString()));
        context.waitTicks(20);
        context.runOnClient(client -> client.player.connection.sendChat("hello from the gametest"));
        context.waitTicks(40);
        context.takeScreenshot("chat-after-send");
        report.add("C2 system_messages=" + system + " player_chat=" + playerChat);
    }

    /**
     * S2: the same scene through each camera {@link SpikeCamera#fromEnvironment} names, one 1920x1080
     * screenshot {@code s2-<label>} each (the 854x480 window leaves a unit a few pixels wide). The HUD
     * is off and the camera type is third person, so the player's hand is not drawn over the scene
     * and the player's body is.
     */
    private static void cameraShapes(ClientGameTestContext context, List<String> report) {
        final List<SpikeCamera.Settings> shapes = context.computeOnClient(client -> {
            client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
            return SpikeCamera.fromEnvironment(client.player.position(), client.player.getYRot());
        });
        for (SpikeCamera.Settings shape : shapes) {
            context.runOnClient(_ -> SpikeCamera.set(shape));
            context.waitTicks(100);
            report.add(context.computeOnClient(client -> {
                final Frustum frustum = client.gameRenderer.mainCamera().getCullFrustum();
                int units = 0;
                int inFrustum = 0;
                for (Entity entity : client.level.entitiesForRendering()) {
                    if (!(entity instanceof Display.ItemDisplay)) continue;
                    units++;
                    if (frustum.isVisible(new AABB(entity.blockPosition()))) inFrustum++;
                }
                return "S2 " + shape.label() + " anchor " + shape.anchor() + " yaw " + shape.yaw()
                        + " eye " + client.gameRenderer.mainCamera().position()
                        + " item_display " + inFrustum + " in frustum of " + units
                        + " render_distance " + client.options.getEffectiveRenderDistance();
            }));
            context.takeScreenshot(TestScreenshotOptions.of("s2-" + shape.label()).withSize(1920, 1080));
        }
        context.runOnClient(_ -> SpikeCamera.set(null));
    }

    /** S4 cursor points, window pixels at 1920x1080: the four corners 40 pixels in, and the centre. */
    private static final int[][] CURSOR_POINTS = {{40, 40}, {1880, 40}, {40, 1040}, {1880, 1040}, {960, 540}};
    private static final String[] CURSOR_NAMES = {"tl", "tr", "bl", "br", "c"};

    private record RayShape(String label, SpikeCamera.Settings settings, int fov) {

        static RayShape of(SpikeCamera.Mode mode, Vec3 anchor, float yaw, float distance, float orthoHeight, int fov) {
            final SpikeCamera.Settings settings = new SpikeCamera.Settings(mode, anchor, yaw, 60f, distance, orthoHeight);
            return new RayShape(fov == 70 ? settings.label() : settings.label() + "-fov" + fov, settings, fov);
        }
    }

    /**
     * S4: for each camera shape and cursor point, the ray under the cursor and two screenshots, the
     * hit point's marker in {@code s4-<shape>-<point>} and the hit face in its {@code -face} twin
     * (see {@link ScreenRay.Layer}). Then the camera pans one block per tick and
     * {@code s4-pan-<shape>-<point>-t<partial tick>} are taken at partial ticks 0.25, 0.5 and 0.75,
     * each with a {@code -tick} twin showing the tick-position marker. The window is 1920x1080, so
     * window pixels are screenshot pixels. Each shot adds an {@code S4} report line;
     * gametest-env/s4_measure.py reads the pixel errors off the screenshots.
     */
    private static void screenRay(ClientGameTestContext context, List<String> report) {
        ClientTickEvents.END_CLIENT_TICK.register(_ -> ScreenRay.tick());
        context.getInput().resizeWindow(1920, 1080);
        context.runOnClient(client -> client.mouseHandler.releaseMouse());
        final Vec3 feet = context.computeOnClient(client -> client.player.position());
        final float yaw = context.computeOnClient(client -> client.player.getYRot());
        final List<RayShape> shapes = List.of(
                RayShape.of(SpikeCamera.Mode.ORTHO, feet, yaw, 32, 24, 70),
                RayShape.of(SpikeCamera.Mode.ORTHO, feet, yaw, 64, 96, 70),
                RayShape.of(SpikeCamera.Mode.ORTHO, feet, yaw, 36, 120, 70),
                RayShape.of(SpikeCamera.Mode.ORTHO, feet, yaw, 240, 96, 70),
                RayShape.of(SpikeCamera.Mode.PERSPECTIVE, feet, yaw, 16, 0, 70),
                RayShape.of(SpikeCamera.Mode.PERSPECTIVE, feet, yaw, 48, 0, 70),
                RayShape.of(SpikeCamera.Mode.PERSPECTIVE, feet, yaw, 64, 0, 30));
        ScreenRay.setActive(true);
        for (RayShape shape : shapes) {
            context.runOnClient(client -> {
                client.options.fov().set(shape.fov());
                SpikeCamera.set(shape.settings());
            });
            context.waitTicks(100);
            for (int i = 0; i < CURSOR_POINTS.length; i++) {
                final String name = "s4-" + shape.label() + "-" + CURSOR_NAMES[i];
                shootRay(context, report, name, CURSOR_POINTS[i], 1f, ScreenRay.Layer.MARKER);
                shootRay(context, report, name + "-face", CURSOR_POINTS[i], 1f, ScreenRay.Layer.FACE);
            }
        }
        context.runOnClient(client -> client.options.fov().set(70));

        // Pan along the screen's x axis: a camera a fraction of a tick off shows up as a sideways shift.
        final Vec3 perTick = new Vec3(Math.cos(Math.toRadians(yaw)), 0, Math.sin(Math.toRadians(yaw)));
        for (RayShape pan : List.of(RayShape.of(SpikeCamera.Mode.ORTHO, feet, yaw, 64, 48, 70),
                RayShape.of(SpikeCamera.Mode.PERSPECTIVE, feet, yaw, 32, 0, 70))) {
            ScreenRay.startPan(pan.settings(), perTick);
            context.waitTicks(20);
            for (int i : new int[] {4, 3}) {
                for (float partialTick : new float[] {0.25f, 0.5f, 0.75f}) {
                    final String name = "s4-pan-" + pan.label() + "-" + CURSOR_NAMES[i] + "-t" + Math.round(partialTick * 100);
                    shootRay(context, report, name, CURSOR_POINTS[i], partialTick, ScreenRay.Layer.MARKER);
                    shootRay(context, report, name + "-tick", CURSOR_POINTS[i], partialTick, ScreenRay.Layer.TICK_MARKER);
                }
            }
            ScreenRay.stopPan();
        }
        ScreenRay.setActive(false);
        context.runOnClient(_ -> SpikeCamera.set(null));
        context.getInput().resizeWindow(854, 480);
    }

    private static void shootRay(ClientGameTestContext context, List<String> report, String name, int[] cursor,
            float partialTick, ScreenRay.Layer layer) {
        ScreenRay.draw(layer);
        context.getInput().setCursorPos(cursor[0], cursor[1]);
        context.takeScreenshot(TestScreenshotOptions.of(name).withDeltaTicks(partialTick));
        report.add(context.computeOnClient(_ -> "S4 " + name + " " + describe(ScreenRay.last())));
    }

    private static String describe(ScreenRay.@Nullable Measurement m) {
        if (m == null) return "no measurement";
        return String.format(Locale.ROOT, "cursor %.1f,%.1f partial_tick %.2f eye %s ray %s dir %s hit %s tick_hit %s uncentred_shift_px %.1f",
                m.cursorX(), m.cursorY(), m.partialTick(), xyz(m.eye()), xyz(m.rayFrom()), xyz(m.rayDirection()),
                where(m.hit()), where(m.tickHit()), m.uncentredShiftPx());
    }

    private static String xyz(Vec3 v) {
        return String.format(Locale.ROOT, "%.3f,%.3f,%.3f", v.x, v.y, v.z);
    }

    private static String where(@Nullable BlockHitResult hit) {
        if (hit == null) return "none";
        final Vec3 at = hit.getLocation();
        return String.format(Locale.ROOT, "%d,%d,%d@%.2f,%.2f,%.2f", hit.getBlockPos().getX(), hit.getBlockPos().getY(),
                hit.getBlockPos().getZ(), at.x, at.y, at.z);
    }

    /**
     * S5: section visibility with the eye high and outside the map ({@link TerrainSpike}), then the
     * two input paths ({@link InputSpike}) under the S2 default camera so the ground is what the
     * cursor points at. HUD and camera type are as {@link #cameraShapes} leaves them.
     */
    private static void sectionsAndInput(ClientGameTestContext context, List<String> report) {
        context.runOnClient(client -> {
            client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
        });
        final SpikeCamera.Settings view = context.computeOnClient(client -> new SpikeCamera.Settings(
                SpikeCamera.Mode.PERSPECTIVE, client.player.position(), client.player.getYRot(), 60f, 64f, 96f));
        context.runOnClient(_ -> SpikeCamera.set(view));
        context.waitTicks(20);
        InputSpike.run(context, report);
        context.runOnClient(_ -> SpikeCamera.set(null));
        TerrainSpike.run(context, report);
    }

    /** Whether SYNAXIS_SPIKES (comma separated, e.g. {@code s2,s5}) names a spike; {@code all} runs S2 to S5. */
    private static boolean enabled(String spike) {
        final List<String> spikes = List.of(System.getenv("SYNAXIS_SPIKES").replace(" ", "").split(","));
        return spikes.contains("all") || spikes.contains(spike);
    }

    private static void write(List<String> report) {
        final Path file = FabricLoader.getInstance().getGameDir().resolve("synaxis-gametest/report.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, report, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("could not write " + file, e);
        }
    }
}
