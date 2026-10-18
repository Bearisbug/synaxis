package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.event.server.ServerListPingEvent;
import io.github.bearisbug.synaxis.listener.preplay.StatusListener;
import io.github.bearisbug.synaxis.network.packet.client.status.StatusRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.status.ResponsePacket;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.testing.Env;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The second half of the {@code REQ-PROD-037} scenario of {@code docs/DESIGN-PROD.md} §19: a
 * plugin listening through its own event capability changes the server list, and when it tries to
 * show a limit below the real one it is refused, the refusal is logged against it and the answer
 * still goes out with the real limit.
 */
class ServerListPingPluginTest {

    @TempDir
    Path workingDirectory;

    private record Lister() implements SynaxisPlugin {
        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor("lister", "1.0.0", PluginApi.API_VERSION);
        }

        @Override
        public void onEnable(PluginContext ctx) {
            ctx.events().addListener(ServerListPingEvent.class, event -> {
                event.setDescription(Component.text("from the plugin"));
                event.setMaxPlayers(100);
                event.setOnlinePlayers(7); // never reached, the line above throws
            });
        }
    }

    @Test
    void aPluginCannotLowerTheLimitAndTheAnswerStillGoesOut() throws ExecutionException, InterruptedException {
        final Env env = Env.create(config -> config.maxPlayers(300));
        final EventLedger ledger = new EventLedger(workingDirectory.resolve("logs"), "test",
                EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        ledger.start();
        try (LogCapture log = LogCapture.of(PluginEventsImpl.class)) {
            new PluginHost(env.process(), workingDirectory.resolve("plugins"), ledger)
                    .enable(List.of(new Lister()), Set.of());

            final List<SendablePacket> sent = new CopyOnWriteArrayList<>();
            final PlayerConnection connection = new PlayerConnection(env.process()) {
                @Override
                public void sendPacket(SendablePacket packet) {
                    sent.add(packet);
                }

                @Override
                public SocketAddress getRemoteAddress() {
                    return new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
                }
            };
            // On a virtual thread, as the read thread of a socket connection is
            try (ExecutorService readThread = Executors.newVirtualThreadPerTaskExecutor()) {
                readThread.submit(() -> StatusListener.requestListener(new StatusRequestPacket(), connection)).get();
            }

            assertEquals(1, sent.size(), "the answer is sent although the listener threw");
            final JsonObject status = JsonParser.parseString(
                    assertInstanceOf(ResponsePacket.class, sent.getFirst()).jsonResponse()).getAsJsonObject();
            final JsonObject players = status.getAsJsonObject("players");
            assertEquals(300, players.get("max").getAsInt(), "the real limit stays");
            assertEquals(0, players.get("online").getAsInt());
            assertEquals(Component.text("from the plugin"),
                    GsonComponentSerializer.gson().deserializeFromTree(status.get("description")),
                    "what the plugin set before the refusal is kept");
            assertEquals(List.of("Plugin event listener failed: plugin_id=lister event=ServerListPingEvent"
                    + " (1 failures since the last report)"), log.messagesAt(Level.ERROR));
        } finally {
            ledger.close();
            env.close();
        }
    }
}
