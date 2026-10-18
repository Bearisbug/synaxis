package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.app.command.HostCommands;
import io.github.bearisbug.synaxis.app.command.Profiler;
import io.github.bearisbug.synaxis.app.store.MemoryBackend;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.command.ConsoleSender;
import io.github.bearisbug.synaxis.command.builder.Command;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.play.DeclareCommandsPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.Permissions;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import io.github.bearisbug.synaxis.thread.TickThread;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the host does around a plugin's command ({@code docs/DESIGN-PROD.md} {@code API-PROD-011}): whatever it
 * throws ends in the plugin's budget, its command line is logged on one line, and the command tree a register sends
 * is built on each player's tick thread.
 */
@EnvTest
class PluginCommandsIntegrationTest {

    @TempDir
    Path workingDirectory;

    private @Nullable EventLedger ledger;
    private final List<Map<String, ?>> events = new CopyOnWriteArrayList<>();
    private final Permissions permissions = new Permissions() {
        @Override
        public boolean has(Player player, String node) {
            return false;
        }

        @Override
        public Set<String> roles(Player player) {
            return Set.of();
        }
    };

    @AfterEach
    void closeLedger() {
        if (ledger != null) ledger.close();
    }

    /** A plugin that lets every player into one match and does in onEnable what the test hands it. */
    private static final class Probe implements SynaxisPlugin {
        private final PluginDescriptor descriptor;
        private final Instance match;
        private final Consumer<PluginContext> onEnable;
        private @Nullable PluginContext context;

        Probe(String id, Instance match, Consumer<PluginContext> onEnable) {
            this.descriptor = new PluginDescriptor(id, "1.0.0", PluginApi.API_VERSION);
            this.match = match;
            this.onEnable = onEnable;
        }

        @Override
        public PluginDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public void onEnable(PluginContext ctx) {
            this.context = ctx;
            ctx.admission().register(_ -> new Admission.Accept(match, 1));
            onEnable.accept(ctx);
        }

        @Override
        public void onDisable() {
        }

        PluginContext context() {
            return Objects.requireNonNull(context);
        }
    }

    /** The host of a process without a database, its commands registered first as in Main. */
    private PluginHost host(Env env) {
        ledger = new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        ledger.start();
        final PlayerStore store = new PlayerStore(env.process(), new MemoryBackend(), RedisLink.disabled("test"), "test",
                false, PlayerStore.Options.DEFAULT, ledger::record);
        new HostCommands(env.process(), permissions, store, (_, attributes) -> events.add(attributes),
                new Profiler(workingDirectory.resolve("profiles"), "test", Clock.systemUTC())).register();
        return new PluginHost(env.process(), workingDirectory.resolve("plugins"), ledger, store);
    }

    /** The names in the last command tree the collector saw, which has to have seen one. */
    private static Set<String> lastTree(Collector<DeclareCommandsPacket> trees) {
        final List<DeclareCommandsPacket> sent = trees.collect();
        assertFalse(sent.isEmpty(), "no command tree came");
        return sent.getLast().nodes().stream().map(node -> node.name).collect(Collectors.toSet());
    }

    /**
     * What a plugin command throws, an {@link Error} included, ends in the plugin's budget: the console thread goes on
     * to read the next line, the one that could stop the server.
     */
    @Test
    void aPluginCommandThatThrowsAnErrorLeavesTheConsoleReadingTheNextLine(Env env) {
        final Instance match = env.createFlatInstance();
        final Command party = new Command("party");
        party.setDefaultExecutor((_, _) -> {
            throw new AssertionError("the party is over");
        });
        final Probe mmo = new Probe("mmo", match, ctx -> ctx.commands().register(party));
        final PluginHost host = host(env);
        host.enable(List.of(mmo), Set.of());

        try (LogCapture plugins = LogCapture.of(PluginCommandsImpl.class)) {
            Main.readConsole(env.process(), new BufferedReader(new StringReader("party\nlist\n")));
            assertEquals(List.of("Plugin command failed: plugin_id=mmo command=party (1 failures since the last report)"),
                    plugins.messagesAt(Level.ERROR));
            assertEquals(AssertionError.class.getName(),
                    plugins.eventsAt(Level.ERROR).getFirst().getThrowableProxy().getClassName());
        }
        assertEquals(List.of("console list"),
                events.stream().map(e -> e.get("actor") + " " + e.get("command")).toList(), "the line after it ran");
        host.disableAll();
    }

    /**
     * A command line is the client's text: logged, it is escaped onto one line, so that it can neither forge a line
     * of its own nor move the cursor of the terminal showing the log.
     */
    @Test
    void aCommandLineIsLoggedOnOneLine(Env env) throws ClassNotFoundException {
        final Instance match = env.createFlatInstance();
        final Command party = new Command("party");
        party.setDefaultExecutor((_, _) -> {
            throw new IllegalStateException("the party is over");
        });
        final Probe mmo = new Probe("mmo", match, ctx -> ctx.commands().register(party));
        final PluginHost host = host(env);
        host.enable(List.of(mmo), Set.of());
        final Command echo = new Command("echo");
        echo.setDefaultExecutor((_, _) -> {
            throw new IllegalStateException("no echo");
        });
        env.process().command().register(echo);
        final ConsoleSender console = new ConsoleSender(env.process());
        final String forged = " a\n00:00:00.000 [main] INFO  forged\r\u001b[1A\u2028\u202e\\u0041";
        final String escaped = " a\\u000a00:00:00.000 [main] INFO  forged\\u000d\\u001b[1A\\u2028\\u202e\\\\u0041";

        try (LogCapture plugins = LogCapture.of(PluginCommandsImpl.class);
             LogCapture engine = LogCapture.of(Class.forName("io.github.bearisbug.synaxis.command.CommandParserImpl"))) {
            env.process().command().execute(console, "mmo:party" + forged);
            env.process().command().execute(console, "echo" + forged);
            assertEquals(List.of("Plugin command failed: plugin_id=mmo command=mmo:party" + escaped
                    + " (1 failures since the last report)"), plugins.messagesAt(Level.ERROR));
            assertEquals(List.of("An exception was encountered while executing command: echo" + escaped),
                    engine.messagesAt(Level.ERROR));
        }
        host.disableAll();
    }

    /**
     * The command tree a register sends to the players online is built on each player's tick thread, which is where
     * a plugin's condition is asked: a condition reading what the tick thread owns is not asked on the thread that
     * called register.
     */
    @Test
    void theTreeARegisterSendsIsBuiltOnThePlayersTickThread(Env env) {
        final Instance match = env.createFlatInstance();
        final Probe mmo = new Probe("mmo", match, _ -> { });
        final PluginHost host = host(env);
        host.enable(List.of(mmo), Set.of());
        final TestConnection connection = env.createConnection(new GameProfile(UUID.randomUUID(), "Watcher"));
        connection.connect(match, new Pos(0, 41, 0));

        final List<Thread> asked = new CopyOnWriteArrayList<>();
        final Command party = new Command("party");
        party.setCondition((_, commandString) -> {
            if (commandString == null) asked.add(Thread.currentThread());
            return true;
        });
        party.setDefaultExecutor((_, _) -> { });
        final Collector<DeclareCommandsPacket> trees = connection.trackIncoming(DeclareCommandsPacket.class);
        mmo.context().commands().register(party);
        env.tick();
        assertTrue(lastTree(trees).contains("party"));
        assertFalse(asked.isEmpty(), "the condition was not asked");
        assertTrue(asked.stream().allMatch(TickThread.class::isInstance), asked.toString());
        host.disableAll();
    }
}
