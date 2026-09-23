package io.github.bearisbug.synaxis.game.client.gametest;

import io.github.bearisbug.synaxis.game.client.ClientHandshake;
import io.github.bearisbug.synaxis.game.client.GamePayload;
import io.github.bearisbug.synaxis.game.client.MinecraftWorldView;
import io.github.bearisbug.synaxis.game.client.OrderFeedback;
import io.github.bearisbug.synaxis.game.client.RtsClient;
import io.github.bearisbug.synaxis.game.client.SynaxisClientMod;
import io.github.bearisbug.synaxis.game.client.UnitBindings;
import io.github.bearisbug.synaxis.game.client.UnitPicker;
import io.github.bearisbug.synaxis.game.protocol.ArchetypeBox;
import io.github.bearisbug.synaxis.game.protocol.OrderResultCode;
import io.github.bearisbug.synaxis.game.protocol.S2COrderResult;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The regression assertions R1 to R8 of {@code API-PROD-026} ({@code REQ-PROD-030}), run by the real
 * client against a server-app in game mode.
 * <p>
 * R1 the mod and its nested :protocol are loaded, R2 the configuration phase handshake is accepted,
 * R3 the client reaches the world, R4 it binds units it may command, R5 it selects one, R6 an order
 * moves that unit at least 2.0 blocks, R7 the sky light the server sent is 15 above the ground and 0
 * below it, R8 a protocol error disconnects with a readable sentence rather than the raw key. R8 is
 * last because it ends the connection.
 * <p>
 * Every assertion is one line {@code R<n> PASS|FAIL <evidence>} of
 * {@code synaxis-gametest/report.txt}, written in a {@code finally} so that a step that throws still
 * leaves the lines before it, and the ones it never reached as {@code FAIL}. The file is what CI
 * judges; the test then fails on any {@code FAIL} as well, but its exit code is only recorded,
 * because the client's exit code is wrong in both directions (a segfault in libopenal on the way
 * out in the container, success when the connection dropped).
 * <p>
 * Selecting and ordering go through {@link RtsClient#selectAt} and {@link RtsClient#orderAt}
 * directly, read by reflection off {@code SynaxisClientMod.RTS}: stage 6b has not wired mouse input
 * to them yet. A renamed field fails R2 or R4 instead of passing silently. Once input is wired, R5
 * and R6 move to {@code TestInput} clicks and the reflection goes.
 */
public final class RegressionClientGameTest implements FabricClientGameTest {

    /** The id Loom gives the :protocol jar nested in the mod. */
    private static final String PROTOCOL_MOD_ID = "io_github_bearisbug_synaxis_protocol";

    private static final int CONNECT_TIMEOUT_TICKS = 20 * 60;
    private static final int UNITS_TIMEOUT_TICKS = 200;
    private static final int SELECT_TIMEOUT_TICKS = 40;
    private static final int ORDER_RESULT_TIMEOUT_TICKS = 40;
    private static final int MOVE_TIMEOUT_TICKS = 100;
    private static final int LIGHT_TIMEOUT_TICKS = 100;
    private static final int DISCONNECT_TIMEOUT_TICKS = 200;

    private static final double ORDER_DISTANCE = 8.0;
    private static final double MIN_DISPLACEMENT = 2.0;
    /** How far above the unit an order's ray starts; the map's obstacles are 3 blocks high. */
    private static final double ORDER_RAY_HEIGHT = 16.0;

    private static final String PROTOCOL_ERROR_KEY = "synaxis:protocol-error";

    @Override
    public void runTest(ClientGameTestContext context) {
        final Report report = new Report();
        try {
            run(context, report);
        } finally {
            report.write();
            leaveToTitle(context);
        }
        report.assertAllPassed();
    }

    private static void run(ClientGameTestContext context, Report report) {
        context.waitForScreen(TitleScreen.class);
        r1ModsLoaded(report);

        final String server = System.getenv("SYNAXIS_GAMETEST_SERVER");
        if (server == null || server.isBlank()) {
            report.skipRest("SYNAXIS_GAMETEST_SERVER not set");
            return;
        }
        // The gametest harness drops the render distance to 5 chunks; 10 is server-app's own cap.
        context.runOnClient(client -> client.options.renderDistance().set(10));
        context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client,
                ServerAddress.parseString(server), new ServerData("synaxis", server, ServerData.Type.OTHER),
                false, null));
        waitUntil(context, client -> inWorld(client) || client.gui.screen() instanceof DisconnectedScreen,
                CONNECT_TIMEOUT_TICKS);

        final ClientHandshake handshake = staticField(ClientHandshake.class, "HANDSHAKE");
        report.put(2, context.computeOnClient(_ -> handshake.state() == ClientHandshake.State.VERIFIED),
                context.computeOnClient(_ -> "state=" + handshake.state()
                        + (handshake.serverHello() == null ? "" : " schema_version=" + handshake.serverHello().schemaVersion())));
        final boolean inWorld = context.computeOnClient(RegressionClientGameTest::inWorld);
        report.put(3, inWorld, context.computeOnClient(client -> inWorld
                ? "in world at " + client.player.blockPosition()
                : "not in world: " + screenText(client)));
        context.takeScreenshot("r3-after-connect");
        if (!inWorld) {
            report.skipRest("R3 failed");
            return;
        }

        final RtsClient rts = staticField(RtsClient.class, "RTS");
        final boolean bound = waitUntil(context, _ -> commandable(rts).isPresent(), UNITS_TIMEOUT_TICKS);
        report.put(4, bound, context.computeOnClient(_ -> "bound=" + rts.bindings().all().size()
                + " commandable=" + rts.bindings().all().stream().filter(UnitBindings.Binding::commandable).count()
                + " unmatched_state_entries=" + rts.bindings().unmatchedStateEntries()));
        if (!bound) {
            report.skipRest("R4 failed");
            return;
        }

        final UnitBindings.@Nullable Binding unit = context.computeOnClient(client -> select(client, rts));
        final boolean selected = unit != null
                && waitUntil(context, _ -> Integer.valueOf(unit.unitId()).equals(rts.selected()), SELECT_TIMEOUT_TICKS);
        report.put(5, selected, unit == null
                ? "no commandable unit is the first one a ray from the camera enters"
                : "unit_id=" + unit.unitId() + " selected=" + context.computeOnClient(_ -> rts.selected()));
        if (!selected) {
            report.skipRest("R5 failed");
            return;
        }

        r6OrderMoves(context, report, rts, unit);
        r7Light(context, report);
        r8ReadableDisconnect(context, report);
    }

    private static void r1ModsLoaded(Report report) {
        final List<String> found = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            final String id = mod.getMetadata().getId();
            if (id.equals("synaxis") || id.equals(PROTOCOL_MOD_ID))
                found.add(id + " " + mod.getMetadata().getVersion().getFriendlyString());
        }
        report.put(1, found.size() == 2, "mods " + found);
    }

    /**
     * Orders the unit 8 blocks away along each axis in turn until an order comes back {@code OK},
     * then waits for the unit's entity to cover {@link #MIN_DISPLACEMENT} horizontally. Each order's
     * ray starts above the target and points straight down, so what it hits is the ground there and
     * not whatever lies between the camera and it.
     */
    private static void r6OrderMoves(ClientGameTestContext context, Report report, RtsClient rts, UnitBindings.Binding unit) {
        final Vec3 start = context.computeOnClient(client -> entityPosition(client, unit));
        if (start == null) {
            report.put(6, false, "the entity of unit_id=" + unit.unitId() + " is not in the level");
            return;
        }
        final List<String> answers = new ArrayList<>();
        OrderFeedback accepted = null;
        for (Vec3 axis : List.of(new Vec3(1, 0, 0), new Vec3(0, 0, 1), new Vec3(-1, 0, 0), new Vec3(0, 0, -1))) {
            final Vec3 above = start.add(axis.scale(ORDER_DISTANCE)).add(0, ORDER_RAY_HEIGHT, 0);
            final OrderFeedback before = context.computeOnClient(_ -> rts.feedback());
            context.runOnClient(_ -> rts.orderAt(above, new Vec3(0, -1, 0)));
            final boolean answered = waitUntil(context, _ -> rts.feedback() != before, ORDER_RESULT_TIMEOUT_TICKS);
            final OrderFeedback answer = context.computeOnClient(_ -> rts.feedback());
            answers.add(answered ? answer.code().name() : "no answer");
            if (answered && answer.code() == OrderResultCode.OK) {
                accepted = answer;
                break;
            }
        }
        if (accepted == null) {
            report.put(6, false, "no order came back OK: " + answers);
            return;
        }
        final boolean moved = waitUntil(context, client -> horizontal(start, entityPosition(client, unit)) >= MIN_DISPLACEMENT,
                MOVE_TIMEOUT_TICKS);
        final double distance = context.computeOnClient(client -> horizontal(start, entityPosition(client, unit)));
        report.put(6, moved, String.format(Locale.ROOT, "unit_id=%d answers %s target %s moved %.2f blocks (need %.1f)",
                unit.unitId(), answers, xyz(accepted.point()), distance, MIN_DISPLACEMENT));
    }

    /**
     * The column the player stands in: sky light 15 in the air on the surface, 0 in the block under
     * the surface block — the one assertion the light parity debt of docs/DESIGN.md §28 has, read
     * off what the client received.
     */
    private static void r7Light(ClientGameTestContext context, Report report) {
        final boolean lit = waitUntil(context, client -> {
            final BlockPos surface = surface(client);
            return surface != null && sky(client, surface.above()) == 15 && sky(client, surface.below()) == 0;
        }, LIGHT_TIMEOUT_TICKS);
        report.put(7, lit, context.computeOnClient(client -> {
            final BlockPos surface = surface(client);
            if (surface == null) return "no solid block under " + client.player.blockPosition();
            return "surface " + surface + " sky_above=" + sky(client, surface.above())
                    + " sky_below=" + sky(client, surface.below());
        }));
        context.takeScreenshot("r7-in-world");
    }

    /**
     * Sends a message only the server sends, which the server answers with
     * {@code synaxis:protocol-error}; the disconnect screen has to show the mod's sentence for that
     * key, not the key.
     */
    private static void r8ReadableDisconnect(ClientGameTestContext context, Report report) {
        context.runOnClient(_ -> ClientPlayNetworking.send(new GamePayload(
                new S2COrderResult(0, OrderResultCode.OK, 0f, 0f, 0f))));
        final boolean disconnected = waitUntil(context, client -> client.gui.screen() instanceof DisconnectedScreen,
                DISCONNECT_TIMEOUT_TICKS);
        final String text = context.computeOnClient(RegressionClientGameTest::screenText);
        final String sentence = context.computeOnClient(_ -> Language.getInstance().getOrDefault(PROTOCOL_ERROR_KEY));
        report.put(8, disconnected && !sentence.equals(PROTOCOL_ERROR_KEY) && text.contains(sentence) && !text.contains("synaxis:"),
                (disconnected ? "" : "no disconnect screen; ") + "screen: " + text);
        context.takeScreenshot("r8-disconnected");
    }

    private static Optional<UnitBindings.Binding> commandable(RtsClient rts) {
        return rts.bindings().all().stream().filter(UnitBindings.Binding::commandable).findFirst();
    }

    /**
     * Selects the first commandable unit that a ray from the camera to the middle of its box enters
     * before any other unit's box.
     */
    private static UnitBindings.@Nullable Binding select(Minecraft client, RtsClient rts) {
        final MinecraftWorldView world = new MinecraftWorldView();
        final Vec3 eye = client.gameRenderer.mainCamera().position();
        for (UnitBindings.Binding binding : List.copyOf(rts.bindings().all())) {
            if (!binding.commandable()) continue;
            final Vec3 feet = world.renderPosition(binding.entityId());
            if (feet == null) continue;
            final Vec3 direction = feet.add(0, ArchetypeBox.of(binding.archetype()).height() / 2.0, 0).subtract(eye);
            if (UnitPicker.pick(eye, direction, RtsClient.REACH, rts.bindings(), world) != binding) continue;
            rts.selectAt(eye, direction);
            return binding;
        }
        return null;
    }

    private static @Nullable Vec3 entityPosition(Minecraft client, UnitBindings.Binding unit) {
        final Entity entity = client.level == null ? null : client.level.getEntity(unit.entityId());
        return entity == null ? null : entity.position();
    }

    private static double horizontal(Vec3 from, @Nullable Vec3 to) {
        if (to == null) return 0;
        return Math.hypot(to.x - from.x, to.z - from.z);
    }

    /** The topmost non-air block at or below the player's feet, or null if there is none within 64. */
    private static @Nullable BlockPos surface(Minecraft client) {
        BlockPos pos = client.player.blockPosition();
        for (int i = 0; i < 64; i++, pos = pos.below()) {
            if (!client.level.getBlockState(pos).isAir()) return pos;
        }
        return null;
    }

    private static int sky(Minecraft client, BlockPos pos) {
        return client.level.getBrightness(LightLayer.SKY, pos);
    }

    private static boolean inWorld(Minecraft client) {
        return client.level != null && client.player != null;
    }

    private static String screenText(Minecraft client) {
        return client.gui.screen() == null ? "no screen" : client.gui.screen().getNarrationMessage().getString();
    }

    private static String xyz(Vec3 v) {
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f", v.x, v.y, v.z);
    }

    /** {@link ClientGameTestContext#waitFor} that answers false on a timeout instead of throwing. */
    private static boolean waitUntil(ClientGameTestContext context, Predicate<Minecraft> condition, int ticks) {
        try {
            context.waitFor(condition, ticks);
            return true;
        } catch (AssertionError _) {
            return false;
        }
    }

    private static <T> T staticField(Class<T> type, String name) {
        try {
            final Field field = SynaxisClientMod.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(null));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SynaxisClientMod." + name + " is not readable; the regression reads it by reflection", e);
        }
    }

    private static void leaveToTitle(ClientGameTestContext context) {
        if (context.computeOnClient(client -> client.level != null)) {
            context.runOnClient(client -> client.disconnect(new TitleScreen(), false));
        } else if (!context.computeOnClient(client -> client.gui.screen() instanceof TitleScreen)) {
            context.setScreen(TitleScreen::new);
        }
        context.waitForScreen(TitleScreen.class);
    }

    /** R1 to R8, one line each, written whole every time so the file never holds a previous run's lines. */
    private static final class Report {

        private static final int ASSERTIONS = 8;

        private final Map<Integer, String> lines = new TreeMap<>();

        void put(int id, boolean pass, String evidence) {
            lines.put(id, "R" + id + (pass ? " PASS " : " FAIL ") + evidence);
        }

        void skipRest(String why) {
            for (int id = 1; id <= ASSERTIONS; id++) lines.putIfAbsent(id, "R" + id + " FAIL not reached: " + why);
        }

        void write() {
            skipRest("the test ended before this assertion");
            final Path file = FabricLoader.getInstance().getGameDir().resolve("synaxis-gametest/report.txt");
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, lines.values(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("could not write " + file, e);
            }
        }

        void assertAllPassed() {
            final List<String> failed = lines.values().stream().filter(line -> !line.contains(" PASS ")).toList();
            if (!failed.isEmpty()) throw new AssertionError("regression assertions failed:\n" + String.join("\n", failed));
        }
    }
}
