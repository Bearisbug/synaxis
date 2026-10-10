package io.github.bearisbug.synaxis.game.client.gametest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * P10 of {@code docs/DESIGN-PROD.md} §19 ({@code REQ-PROD-031}, {@code API-PROD-026}): the real
 * client on two map shards while {@code gametest-env/p10.py drive} sends it from one to the other
 * and back through the management interface's {@code synaxis:transfer}.
 * <p>
 * The client does what a player's does and nothing more: it connects to the shard
 * SYNAXIS_P10_SERVER names, and every {@code Transfer} the shards send is followed by the vanilla
 * client itself, a new connection carrying the ticket cookie. Each world it joins is one line
 * {@code join <n> port=<the server's port> at=<epoch ms>} of {@code synaxis-gametest/p10.txt},
 * followed when the client leaves it by the line {@code state <n> pos=<x>,<y>,<z> yaw=<yaw>
 * pitch=<pitch> held=<selected hotbar slot> inventory=<slot>:<item id>*<count>,...} ({@code empty}
 * for no items; slots are the inventory menu's, window 0 on the wire), the state of the last client
 * tick in that world, or {@code state <n> none} without one. It is taken on leaving because at the
 * join itself the server has sent neither the position nor the inventory yet; the client does
 * nothing, so what it holds on leaving is what the server gave it on arrival, which the judge holds
 * to be the same in every world (a transfer carries the inventory over). The driver ends the run by
 * creating the file SYNAXIS_P10_DONE names; a disconnect screen or {@link #RUN_LIMIT_NANOS} ends it
 * too, and the last line says which one. As with the regression's report, the file is what CI
 * judges and the exit code is only recorded.
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
        final Report report = new Report();
        // On the client thread, once per connection that reaches the play phase: the first one and
        // every one a Transfer opened
        ClientPlayConnectionEvents.JOIN.register((listener, _, _) -> report.join(listener.getConnection().getRemoteAddress()));
        ClientTickEvents.END_CLIENT_TICK.register(report::tick);

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
        context.runOnClient(client -> report.end(client.gui.screen() instanceof DisconnectedScreen
                ? "disconnected: " + client.gui.screen().getNarrationMessage().getString()
                : Files.exists(doneFile) ? "done" : "timeout"));
        write(report.lines);

        if (context.computeOnClient(client -> client.level != null)) {
            context.runOnClient(client -> client.disconnect(new TitleScreen(), false));
        } else {
            context.setScreen(TitleScreen::new);
        }
        context.waitForScreen(TitleScreen.class);
    }

    /** The lines of {@code p10.txt}; the rest is touched on the client thread only. */
    private static final class Report {

        final List<String> lines = new CopyOnWriteArrayList<>();
        private int joins;
        private @Nullable String state;

        void join(SocketAddress server) {
            leave();
            joins++;
            lines.add("join " + joins + " port=" + port(server) + " at=" + System.currentTimeMillis());
        }

        /** Keeps the state of the world the client is in until it leaves; between worlds there is no player. */
        void tick(Minecraft client) {
            if (joins > 0 && client.player != null) state = state(client.player);
        }

        void end(String how) {
            leave();
            lines.add("end " + how);
        }

        private void leave() {
            if (joins > 0) lines.add("state " + joins + " " + (state == null ? "none" : state));
            state = null;
        }
    }

    private static String state(LocalPlayer player) {
        final StringJoiner items = new StringJoiner(",");
        final List<Slot> slots = player.inventoryMenu.slots;
        for (int i = 0; i < slots.size(); i++) {
            final ItemStack stack = slots.get(i).getItem();
            if (!stack.isEmpty()) items.add(i + ":" + BuiltInRegistries.ITEM.getKey(stack.getItem()) + "*" + stack.getCount());
        }
        return String.format(Locale.ROOT, "pos=%.2f,%.2f,%.2f yaw=%.1f pitch=%.1f held=%d inventory=%s",
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
                player.getInventory().getSelectedSlot(), items.length() == 0 ? "empty" : items);
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
