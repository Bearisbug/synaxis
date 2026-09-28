package io.github.bearisbug.synaxis.app.management;

import com.google.gson.JsonObject;
import io.github.bearisbug.synaxis.ServerProcess;
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

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import static io.github.bearisbug.synaxis.app.management.ManagementClient.await;
import static io.github.bearisbug.synaxis.app.management.ManagementSettingsTest.TOKEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code minecraft:server/stop} before Main has started the server ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-022} the {@code server/stop} row, §11.4 below the table, §16 the row of a stop
 * while starting). The interface listens while the plugins are enabled, and a shutdown begun then
 * would run before Main registered its shutdown tasks; so the call is refused with -32000 and the
 * engine is never asked. Once Main says the server has started, the same call stops it.
 */
@EnvTest
class ManagementStopBeforeStartIntegrationTest {

    private static final Permissions NO_NODES = new Permissions() {
        @Override
        public boolean has(Player player, String node) {
            return false;
        }

        @Override
        public Set<String> roles(Player player) {
            return Set.of();
        }
    };

    @TempDir
    Path workingDirectory;

    private final List<Map<String, ?>> events = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> opened = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        for (AutoCloseable closeable : opened.reversed()) closeable.close();
    }

    private List<String> results() {
        return events.stream().map(event -> event.get("command") + " " + event.get("result")).toList();
    }

    @Test
    void aStopBeforeTheServerHasStartedIsRefusedAndStopsNothing(Env env) throws Exception {
        final BiConsumer<String, Map<String, ?>> ledger = (_, attributes) -> events.add(attributes);
        final PlayerStore store = PlayerStore.inMemory(env.process(), (_, _) -> { });
        final HostCommands commands = new HostCommands(env.process(), NO_NODES, store, ledger,
                new Profiler(workingDirectory.resolve("profiles"), "test", Clock.systemUTC()));
        final ManagementMethods methods = new ManagementMethods(env.process(), commands, store,
                HostModeration.withoutDatabase(env.process()), null, null, "test", ledger);
        final ManagementServer server = ManagementServer.serve(ManagementSettings.parse("true", "127.0.0.1:0", TOKEN),
                env.process(), methods, Duration.ofSeconds(5));
        opened.add(server);
        final ManagementClient client = ManagementClient.connect(server.address(), "Bearer " + TOKEN);
        opened.add(client);

        final JsonObject refused = client.call(1, "minecraft:server/stop", null);

        final JsonObject error = refused.getAsJsonObject("error");
        assertNotNull(error, "refused while the server is starting: " + refused);
        assertEquals(ManagementMethods.UNAVAILABLE, error.get("code").getAsInt(), refused.toString());
        assertEquals("The server is still starting; stop it once it has started", error.get("message").getAsString());
        assertEquals(ServerProcess.ShutdownPhase.RUNNING, env.process().shutdownPhase(), "the engine was not asked");
        assertFalse(env.process().connection().gate().isClosed(), "the login gate is still open");
        assertEquals(List.of("minecraft:server/stop unavailable"), results());

        methods.serverStarted();
        final JsonObject stopped = client.call(2, "minecraft:server/stop", null);

        assertTrue(stopped.get("result").getAsBoolean(), "once started, the stop is the engine's: " + stopped);
        await(() -> env.process().shutdownPhase() == ServerProcess.ShutdownPhase.STOPPED, "the process stops");
        assertEquals(List.of("minecraft:server/stop unavailable", "minecraft:server/stop ok"), results());
    }
}
