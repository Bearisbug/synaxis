package io.github.bearisbug.synaxis.game.client.gametest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * P10 of {@code docs/DESIGN-PROD.md} §19 ({@code REQ-PROD-031}, {@code API-PROD-026}): the real
 * client on two map shards while {@code gametest-env/p10.py drive} sends it from one to the other
 * and back through the management interface's {@code synaxis:transfer}.
 * <p>
 * The client does what a player's does and nothing more: it connects to the shard
 * SYNAXIS_P10_SERVER names, and every {@code Transfer} the shards send is followed by the vanilla
 * client itself, a new connection carrying the ticket cookie. Each world it joins is one line
 * {@code join <n> port=<the server's port> at=<epoch ms>} of {@code synaxis-gametest/p10.txt}. The
 * driver ends the run by creating the file SYNAXIS_P10_DONE names; a disconnect screen or
 * {@link #RUN_LIMIT_NANOS} ends it too, and the last line says which one. As with the regression's
 * report, the file is what CI judges and the exit code is only recorded.
 * <p>
 * It runs only when SYNAXIS_P10_SERVER is set, and then alone: {@link RegressionClientGameTest}
 * returns at once, and the spikes run only with SYNAXIS_SPIKES, which a P10 run does not set.
 */
public final class TransferRoundTripClientGameTest implements FabricClientGameTest {

    private static final String SERVER_VARIABLE = "SYNAXIS_P10_SERVER";
    private static final String DONE_VARIABLE = "SYNAXIS_P10_DONE";

    /** Longer than the driver's 200 transfers at least 5.5 s apart, so the driver ends a run. */
    private static final long RUN_LIMIT_NANOS = TimeUnit.MINUTES.toNanos(60);

    /**
     * @return whether this run is a P10 run
     */
    static boolean enabled() {
        final String server = System.getenv(SERVER_VARIABLE);
        return server != null && !server.isBlank();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!enabled()) return;
        final String server = System.getenv(SERVER_VARIABLE);
        final String done = System.getenv(DONE_VARIABLE);
        if (done == null || done.isBlank()) throw new IllegalStateException(SERVER_VARIABLE + " is set without " + DONE_VARIABLE);
        final Path doneFile = Path.of(done);
        final List<String> lines = new CopyOnWriteArrayList<>();
        // On the client thread, once per connection that reaches the play phase: the first one and
        // every one a Transfer opened
        ClientPlayConnectionEvents.JOIN.register((listener, _, _) -> lines.add("join " + (lines.size() + 1)
                + " port=" + port(listener.getConnection().getRemoteAddress()) + " at=" + System.currentTimeMillis()));

        context.waitForScreen(TitleScreen.class);
        // Under the gametest harness every frame is one tick. The 2 core runner carries both shards
        // as well, so the client renders 20 frames a second of the chunks 2 around it.
        context.runOnClient(client -> {
            client.options.renderDistance().set(2);
            client.options.framerateLimit().set(20);
        });
        context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client,
                ServerAddress.parseString(server), new ServerData("synaxis-p10", server, ServerData.Type.OTHER),
                false, null));
        final long deadline = System.nanoTime() + RUN_LIMIT_NANOS;
        context.waitFor(client -> Files.exists(doneFile) || client.gui.screen() instanceof DisconnectedScreen
                || System.nanoTime() - deadline > 0, ClientGameTestContext.NO_TIMEOUT);
        lines.add(context.computeOnClient(client -> client.gui.screen() instanceof DisconnectedScreen
                ? "end disconnected: " + client.gui.screen().getNarrationMessage().getString()
                : Files.exists(doneFile) ? "end done" : "end timeout"));
        write(lines);

        if (context.computeOnClient(client -> client.level != null)) {
            context.runOnClient(client -> client.disconnect(new TitleScreen(), false));
        } else {
            context.setScreen(TitleScreen::new);
        }
        context.waitForScreen(TitleScreen.class);
    }

    private static String port(SocketAddress address) {
        return address instanceof InetSocketAddress inet ? Integer.toString(inet.getPort()) : String.valueOf(address);
    }

    private static void write(List<String> lines) {
        final Path file = FabricLoader.getInstance().getGameDir().resolve("synaxis-gametest/p10.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not write " + file, e);
        }
    }
}
