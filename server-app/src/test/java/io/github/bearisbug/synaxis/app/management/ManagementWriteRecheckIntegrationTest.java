package io.github.bearisbug.synaxis.app.management;

import com.google.gson.JsonObject;
import io.github.bearisbug.synaxis.app.command.HostCommands;
import io.github.bearisbug.synaxis.app.command.Profiler;
import io.github.bearisbug.synaxis.app.moderation.HostModeration;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.Permissions;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.framing.TextFrame;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.github.bearisbug.synaxis.app.management.ManagementClient.await;
import static io.github.bearisbug.synaxis.app.management.ManagementSettingsTest.TOKEN;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A frame left in the outbound queue of a management connection with no write pending is still
 * written ({@code docs/DESIGN-PROD.md} {@code API-PROD-022} the size item, §16 the row of frames
 * queued as the selector empties the queue, §19 the scenario of a reply queued then). Java-WebSocket
 * leaves a frame so when it is queued between the selector thread's last look at the queue it has
 * just written out and its dropping of the write interest; those few instructions are too narrow to
 * hit on purpose, so the test builds what they leave behind: it adds a reply to the queue of the open
 * connection without asking the selector to write it.
 */
@EnvTest
class ManagementWriteRecheckIntegrationTest {

    @TempDir
    Path workingDirectory;

    private final List<AutoCloseable> opened = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        for (AutoCloseable closeable : opened.reversed()) closeable.close();
    }

    private ManagementServer serve(Env env) {
        final PlayerStore store = PlayerStore.inMemory(env.process(), (_, _) -> { });
        final Permissions none = new Permissions() {
            @Override
            public boolean has(Player player, String node) {
                return false;
            }

            @Override
            public Set<String> roles(Player player) {
                return Set.of();
            }
        };
        final HostCommands commands = new HostCommands(env.process(), none, store, (_, _) -> { },
                new Profiler(workingDirectory.resolve("profiles"), "test", Clock.systemUTC()));
        final ManagementMethods methods = new ManagementMethods(env.process(), commands, store,
                HostModeration.withoutDatabase(env.process()), null, null, "test", (_, _) -> { });
        final ManagementServer server = ManagementServer.serve(ManagementSettings.parse("true", "127.0.0.1:0", TOKEN),
                env.process(), methods, Duration.ofSeconds(5));
        opened.add(server);
        return server;
    }

    /** The server's side of the one connection open on it. */
    private static WebSocketImpl onlyConnection(ManagementServer server) throws ReflectiveOperationException {
        final Field field = ManagementServer.class.getDeclaredField("endpoint");
        field.setAccessible(true);
        final WebSocketServer endpoint = (WebSocketServer) field.get(server);
        await(() -> endpoint.getConnections().size() == 1, "the connection open on the server");
        return (WebSocketImpl) endpoint.getConnections().iterator().next();
    }

    @Test
    void aReplyLeftQueuedWithNoWritePendingIsWritten(Env env) throws Exception {
        final ManagementServer server = serve(env);
        final ManagementClient client = ManagementClient.connect(server.address(), "Bearer " + TOKEN);
        opened.add(client);
        final WebSocketImpl connection = onlyConnection(server);
        await(() -> connection.outQueue.isEmpty() && connection.getSelectionKey().interestOps() == SelectionKey.OP_READ,
                "the handshake answer written and only reads awaited");

        final TextFrame reply = new TextFrame();
        reply.setPayload(ByteBuffer.wrap("{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":true}".getBytes(StandardCharsets.UTF_8)));
        final long queued = System.nanoTime();
        connection.outQueue.add(connection.getDraft().createBinaryFrame(reply));
        final JsonObject answer = client.answer(9, null);
        final long millis = (System.nanoTime() - queued) / 1_000_000;

        assertTrue(answer.get("result").getAsBoolean(), answer.toString());
        System.out.println("The reply left queued arrived after " + millis + " ms");
    }
}
