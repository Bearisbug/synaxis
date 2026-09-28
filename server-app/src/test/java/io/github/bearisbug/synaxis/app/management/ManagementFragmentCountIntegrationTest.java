package io.github.bearisbug.synaxis.app.management;

import io.github.bearisbug.synaxis.app.command.HostCommands;
import io.github.bearisbug.synaxis.app.command.Profiler;
import io.github.bearisbug.synaxis.app.moderation.HostModeration;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.Permissions;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.bearisbug.synaxis.app.management.ManagementSettingsTest.TOKEN;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The fragment count of a management message ({@code docs/DESIGN-PROD.md} {@code API-PROD-022},
 * §19 the scenario of empty fragments): Java-WebSocket keeps a buffer for every fragment, and an
 * empty one adds nothing to the length, so the count is bounded as well as the length.
 */
@EnvTest
class ManagementFragmentCountIntegrationTest {

    @TempDir
    Path workingDirectory;

    private final List<Map<String, ?>> events = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> opened = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        for (AutoCloseable closeable : opened.reversed()) closeable.close();
    }

    private InetSocketAddress serve(Env env) {
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
                HostModeration.withoutDatabase(env.process()), null, null, "test", (_, attributes) -> events.add(attributes));
        final ManagementServer server = ManagementServer.serve(ManagementSettings.parse("true", "127.0.0.1:0", TOKEN),
                env.process(), methods, Duration.ofSeconds(5));
        opened.add(server);
        return server.address();
    }

    private ManagementClient connect(InetSocketAddress address) throws Exception {
        final ManagementClient client = ManagementClient.connect(address, "Bearer " + TOKEN);
        opened.add(client);
        return client;
    }

    @Test
    void emptyFragmentsPastTheCountCloseTheConnection(Env env) throws Exception {
        final InetSocketAddress address = serve(env);
        final String head = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"synaxis:broadcast\",\"params\":[{\"literal\":\"";
        final String tail = "x\"}]}";

        // Exactly the count: the head, empty fragments, the tail as the last one
        final ManagementClient fits = connect(address);
        fits.socket().sendText(head, false).get();
        for (int fragment = 2; fragment < ManagementServer.MAX_MESSAGE_FRAGMENTS; fragment++)
            fits.socket().sendText("", false).get();
        fits.socket().sendText(tail, true).get();
        assertEquals(0, fits.answer(1, null).get("result").getAsInt(), "a message in 1,024 fragments runs");

        // Empty fragments that never end: the one past the count closes the connection, the length
        // never got past the head
        final ManagementClient endless = connect(address);
        endless.socket().sendText(head, false).get();
        for (int fragment = 2; fragment <= ManagementServer.MAX_MESSAGE_FRAGMENTS + 1; fragment++)
            endless.socket().sendText("", false).get();
        assertEquals(1009, endless.awaitClose());

        assertEquals(1, events.size(), "only the message within the count ran: " + events);
    }
}
