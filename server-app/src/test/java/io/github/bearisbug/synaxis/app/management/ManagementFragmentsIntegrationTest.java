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
 * The message limit of the management interface against a peer that fragments
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-022}, §19 the scenario of fragments that never end):
 * the running length is held to the limit at every fragment, not only at the first and the last.
 */
@EnvTest
class ManagementFragmentsIntegrationTest {

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
    void fragmentsPastTheLimitCloseTheConnectionWithoutWaitingForTheLastOne(Env env) throws Exception {
        final InetSocketAddress address = serve(env);
        final String head = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"synaxis:broadcast\",\"params\":[{\"literal\":\"";
        final String tail = "\"}]}";

        // Exactly the limit, in three fragments
        final ManagementClient fits = connect(address);
        final String whole = head + "x".repeat(ManagementServer.MAX_MESSAGE_BYTES - head.length() - tail.length()) + tail;
        fits.socket().sendText(whole.substring(0, 30_000), false).get();
        fits.socket().sendText(whole.substring(30_000, 60_000), false).get();
        fits.socket().sendText(whole.substring(60_000), true).get();
        assertEquals(0, fits.answer(1, null).get("result").getAsInt(), "65,536 bytes in three fragments run");

        // Never finished: the third fragment takes it past the limit and the last one never comes
        final ManagementClient endless = connect(address);
        final String fragment = "x".repeat(30_000);
        endless.socket().sendText(head + fragment, false).get();
        endless.socket().sendText(fragment, false).get();
        endless.socket().sendText(fragment, false).get();
        assertEquals(1009, endless.awaitClose());

        assertEquals(1, events.size(), "only the message that fits ran: " + events);
    }
}
