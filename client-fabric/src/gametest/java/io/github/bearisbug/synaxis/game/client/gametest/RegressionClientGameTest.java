package io.github.bearisbug.synaxis.game.client.gametest;

import io.github.bearisbug.synaxis.game.client.ClientHandshake;
import io.github.bearisbug.synaxis.game.client.HandshakePayload;
import io.github.bearisbug.synaxis.game.client.SynaxisClientMod;
import io.github.bearisbug.synaxis.game.client.gametest.mixin.ConnectionAccessor;
import io.github.bearisbug.synaxis.game.protocol.ProtocolSchema;
import io.github.bearisbug.synaxis.game.protocol.S2CHello;
import io.netty.channel.ChannelPipeline;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.world.level.LightLayer;
import org.jspecify.annotations.Nullable;

/**
 * The regression assertions R1 to R5 of {@code API-PROD-026} ({@code REQ-PROD-030}), run by the real
 * client against a server-app.
 * <p>
 * R1 the mod and its nested :protocol are loaded, R2 the configuration phase handshake is accepted
 * (answered, or on a core mode server sent and let into the world),
 * R3 the client reaches the world (in the full online tier, over an encrypted connection), R4 the
 * sky light the server sent is 15 above the ground and 0 below it, R5 a protocol error disconnects
 * with a readable sentence rather than the raw key. R5 is last because it ends the connection.
 * <p>
 * Every assertion is one line {@code R<n> PASS|FAIL <evidence>} of
 * {@code synaxis-gametest/report.txt}, written in a {@code finally} so that a step that throws still
 * leaves the lines before it, and the ones it never reached as {@code FAIL} naming what it threw.
 * The file is what CI judges, and the test never fails itself: Fabric's runner stops at the first
 * test that throws, and {@link PlayableClientGameTest} runs after this one. The client's exit code
 * is only recorded, because it is wrong in both directions (a segfault in libopenal on the way out
 * in the container, success when the connection dropped).
 */
public final class RegressionClientGameTest implements FabricClientGameTest {

    /** The id Loom gives the :protocol jar nested in the mod. */
    private static final String PROTOCOL_MOD_ID = "io_github_bearisbug_synaxis_protocol";

    private static final int CONNECT_TIMEOUT_TICKS = 20 * 60;
    private static final int LIGHT_TIMEOUT_TICKS = 100;
    private static final int DISCONNECT_TIMEOUT_TICKS = 200;

    private static final String PROTOCOL_ERROR_KEY = "synaxis:protocol-error";

    /**
     * Set, with its two siblings, by build.gradle.kts from SYNAXIS_GAMETEST_SESSION_HOST: the full
     * online tier, where the server is in online mode and the connection has to be encrypted.
     */
    static final String SESSION_HOST_PROPERTY = "minecraft.api.session.host";

    @Override
    public void runTest(ClientGameTestContext context) {
        // A P10 run has no SYNAXIS_GAMETEST_SERVER to run R2 to R5 against, and its judge reads
        // p10.txt alone
        if (TransferRoundTripClientGameTest.enabled()) return;
        final Report report = new Report();
        String ended = "the test ended before this assertion";
        try {
            run(context, report);
        } catch (RuntimeException | AssertionError e) {
            ended = "the test threw " + e;
        } finally {
            report.write(ended);
            leaveToTitle(context);
        }
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
        final boolean inWorld = context.computeOnClient(RegressionClientGameTest::inWorld);
        // A core mode server answers the hello on its tick thread without holding the configuration
        // phase for it. A hello it handles after that phase gets its answer in the play phase, where
        // the mod does not listen, and the client stays SENT in the world; the server's acceptance is
        // then the client_verified of its ledger, which the judge counts.
        final ClientHandshake.State state = context.computeOnClient(_ -> handshake.state());
        report.put(2, state == ClientHandshake.State.VERIFIED || (state == ClientHandshake.State.SENT && inWorld),
                context.computeOnClient(_ -> "state=" + handshake.state() + " in_world=" + inWorld
                        + (handshake.serverHello() == null ? "" : " schema_version=" + handshake.serverHello().schemaVersion())));
        final boolean online = System.getProperty(SESSION_HOST_PROPERTY) != null;
        final boolean encrypted = context.computeOnClient(RegressionClientGameTest::encrypted);
        report.put(3, inWorld && (encrypted || !online), context.computeOnClient(client -> inWorld
                ? "in world at " + client.player.blockPosition() + " encrypted=" + encrypted
                        + (online ? " session_host=" + System.getProperty(SESSION_HOST_PROPERTY) : "")
                : "not in world: " + screenText(client)));
        context.takeScreenshot("r3-after-connect");
        if (!inWorld) {
            report.skipRest("R3 failed");
            return;
        }

        r4Light(context, report);
        r5ReadableDisconnect(context, report);
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
     * The column the player stands in: sky light 15 in the air on the surface, 0 in the block under
     * the surface block — the one assertion the light parity debt of docs/DESIGN.md §28 has, read
     * off what the client received.
     */
    private static void r4Light(ClientGameTestContext context, Report report) {
        final boolean lit = waitUntil(context, client -> {
            final BlockPos surface = surface(client);
            return surface != null && sky(client, surface.above()) == 15 && sky(client, surface.below()) == 0;
        }, LIGHT_TIMEOUT_TICKS);
        report.put(4, lit, context.computeOnClient(client -> {
            final BlockPos surface = surface(client);
            if (surface == null) return "no solid block under " + client.player.blockPosition();
            return "surface " + surface + " sky_above=" + sky(client, surface.above())
                    + " sky_below=" + sky(client, surface.below());
        }));
        context.takeScreenshot("r4-in-world");
    }

    /**
     * Sends a message only the server sends, an {@code S2C_HELLO} on {@code synaxis:handshake} after
     * the handshake is over, which the server answers with {@code synaxis:protocol-error} in either
     * mode; the disconnect screen has to show the mod's sentence for that key, not the key. The play
     * phase direction of the channel is registered by {@code PlayTestHook}: the mod itself only
     * sends it during configuration.
     */
    private static void r5ReadableDisconnect(ClientGameTestContext context, Report report) {
        context.runOnClient(_ -> ClientPlayNetworking.send(new HandshakePayload(
                new S2CHello(ProtocolSchema.SCHEMA_VERSION, 20, 0))));
        final boolean disconnected = waitUntil(context, client -> client.gui.screen() instanceof DisconnectedScreen,
                DISCONNECT_TIMEOUT_TICKS);
        final String text = context.computeOnClient(RegressionClientGameTest::screenText);
        final String sentence = context.computeOnClient(_ -> Language.getInstance().getOrDefault(PROTOCOL_ERROR_KEY));
        report.put(5, disconnected && !sentence.equals(PROTOCOL_ERROR_KEY) && text.contains(sentence) && !text.contains("synaxis:"),
                (disconnected ? "" : "no disconnect screen; ") + "screen: " + text);
        context.takeScreenshot("r5-disconnected");
    }

    /** The topmost non-air block at or below the player's feet, or null if there is none within 64. */
    private static @Nullable BlockPos surface(Minecraft client) {
        BlockPos pos = client.player.blockPosition();
        for (int i = 0; i < 64; i++, pos = pos.below()) {
            if (!client.level.getBlockState(pos).isAir()) return pos;
        }
        return null;
    }

    static int sky(Minecraft client, BlockPos pos) {
        return client.level.getBrightness(LightLayer.SKY, pos);
    }

    static boolean inWorld(Minecraft client) {
        return client.level != null && client.player != null;
    }

    /** Whether the connection to the server carries the cipher handlers the key exchange adds. */
    static boolean encrypted(Minecraft client) {
        final ClientPacketListener listener = client.getConnection();
        if (listener == null) return false;
        final ChannelPipeline pipeline = ((ConnectionAccessor) listener.getConnection()).synaxis$channel().pipeline();
        return pipeline.get("decrypt") != null && pipeline.get("encrypt") != null;
    }

    static String screenText(Minecraft client) {
        return client.gui.screen() == null ? "no screen" : client.gui.screen().getNarrationMessage().getString();
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

    static <T> T staticField(Class<T> type, String name) {
        try {
            final Field field = SynaxisClientMod.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(null));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SynaxisClientMod." + name + " is not readable; the regression reads it by reflection", e);
        }
    }

    static void leaveToTitle(ClientGameTestContext context) {
        if (context.computeOnClient(client -> client.level != null)) {
            context.runOnClient(client -> client.disconnect(new TitleScreen(), false));
        } else if (!context.computeOnClient(client -> client.gui.screen() instanceof TitleScreen)) {
            context.setScreen(TitleScreen::new);
        }
        context.waitForScreen(TitleScreen.class);
    }

    /** R1 to R5, one line each, written whole every time so the file never holds a previous run's lines. */
    private static final class Report {

        private static final int ASSERTIONS = 5;

        private final Map<Integer, String> lines = new TreeMap<>();

        void put(int id, boolean pass, String evidence) {
            lines.put(id, "R" + id + (pass ? " PASS " : " FAIL ") + evidence);
        }

        void skipRest(String why) {
            for (int id = 1; id <= ASSERTIONS; id++) lines.putIfAbsent(id, "R" + id + " FAIL not reached: " + why);
        }

        /** Writes the file, every assertion not reached a {@code FAIL} that says why. */
        void write(String why) {
            skipRest(why);
            final Path file = FabricLoader.getInstance().getGameDir().resolve("synaxis-gametest/report.txt");
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, lines.values(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("could not write " + file, e);
            }
        }
    }
}
