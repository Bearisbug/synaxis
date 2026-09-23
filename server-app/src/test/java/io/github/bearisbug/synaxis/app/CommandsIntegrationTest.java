package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.command.HostCommands;
import io.github.bearisbug.synaxis.app.store.MemoryBackend;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.command.ConsoleSender;
import io.github.bearisbug.synaxis.command.builder.Command;
import io.github.bearisbug.synaxis.command.builder.CommandResult;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTabCompletePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.DeclareCommandsPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SystemChatPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.TabCompletePacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.Permissions;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.ServerProcess.ShutdownPhase;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console, the six commands of the host and the commands of plugins
 * ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-012}, {@code API-PROD-011}, §19 the two command
 * scenarios): a console line is a command, a player runs a command of the host only with its node
 * and only sees it among the completions with it, a plugin command lives under the plugin's
 * namespace and leaves with the plugin, and what it throws is logged at most once every 10 s.
 */
@EnvTest
class CommandsIntegrationTest {

    @TempDir
    Path workingDirectory;

    private @Nullable EventLedger ledger;
    private final List<Map<String, ?>> events = new CopyOnWriteArrayList<>();
    /** The nodes each player holds, by name. */
    private final Map<String, Set<String>> nodes = new ConcurrentHashMap<>();
    private final Permissions permissions = new Permissions() {
        @Override
        public boolean has(Player player, String node) {
            return nodes.getOrDefault(player.getUsername(), Set.of()).contains(node);
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

    /** The console, recording every answer as its translate key followed by its arguments. */
    private static final class RecordingConsole extends ConsoleSender {
        final List<String> answers = new CopyOnWriteArrayList<>();

        RecordingConsole(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendMessage(Component message) {
            answers.add(text(message));
        }
    }

    private static String text(Component message) {
        if (message instanceof TranslatableComponent translatable) {
            final String arguments = translatable.arguments().stream()
                    .map(argument -> ((TextComponent) argument.asComponent()).content())
                    .collect(Collectors.joining("|"));
            return arguments.isEmpty() ? translatable.key() : translatable.key() + " " + arguments;
        }
        return message instanceof TextComponent plain ? plain.content() : message.toString();
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
            if (descriptor.id().equals("mmo")) ctx.admission().register(_ -> new Admission.Accept(match, 1));
            onEnable.accept(ctx);
        }

        @Override
        public void onDisable() {
        }

        PluginContext context() {
            return Objects.requireNonNull(context);
        }
    }

    /** The host of a process without a database, its six commands registered first as in Main. */
    private PluginHost host(Env env) {
        ledger = new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        ledger.start();
        final PlayerStore store = new PlayerStore(env.process(), new MemoryBackend(), RedisLink.disabled("test"), "test",
                false, PlayerStore.Options.DEFAULT, ledger::record);
        new HostCommands(env.process(), permissions, store, (_, attributes) -> events.add(attributes)).register();
        return new PluginHost(env.process(), workingDirectory.resolve("plugins"), ledger, store);
    }

    private static void await(Env env, BooleanSupplier condition, Duration within, String what) {
        final long deadline = System.nanoTime() + within.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) throw new AssertionError("not within " + within + ": " + what);
            env.tick();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** The names in the last command tree the collector saw, which has to have seen one. */
    private static Set<String> lastTree(Collector<DeclareCommandsPacket> trees) {
        final List<DeclareCommandsPacket> sent = trees.collect();
        assertFalse(sent.isEmpty(), "no command tree came");
        return sent.getLast().nodes().stream().map(node -> node.name).collect(Collectors.toSet());
    }

    @Test
    void theConsoleRunsListTpsAndSaveAndAnUnknownLineIsOneLine(Env env) {
        final Instance match = env.createFlatInstance();
        final PluginHost host = host(env);
        host.enable(List.of(new Probe("mmo", match, _ -> { })), Set.of());
        env.createConnection(new GameProfile(UUID.randomUUID(), "Bravo")).connect(match, new Pos(0, 41, 0));
        env.createConnection(new GameProfile(UUID.randomUUID(), "Alpha")).connect(match, new Pos(0, 41, 0));
        for (int i = 0; i < 5; i++) env.tick();

        try (LogCapture main = LogCapture.of(Main.class); LogCapture console = LogCapture.of(ConsoleSender.class)) {
            for (String line : List.of("list", "/tps", "  save  "))
                assertEquals(CommandResult.Type.SUCCESS, Main.runConsoleCommand(env.process(), line).getType(), line);
            await(env, () -> console.messagesAt(Level.INFO).size() == 3, Duration.ofSeconds(5), "the save answers");
            final List<String> answers = console.messagesAt(Level.INFO);
            assertEquals("2 players online: Alpha, Bravo", answers.get(0));
            assertTrue(answers.get(1).matches("TPS \\d+\\.\\d, MSPT p50 \\d+\\.\\d ms, p95 \\d+\\.\\d ms, p99 \\d+\\.\\d ms "
                    + "over the last \\d+ ticks"), answers.get(1));
            assertEquals("Saved 2 of 2 players", answers.get(2));

            assertEquals(CommandResult.Type.UNKNOWN, Main.runConsoleCommand(env.process(), "frobnicate now").getType());
            assertEquals(CommandResult.Type.INVALID_SYNTAX, Main.runConsoleCommand(env.process(), "kick").getType());
            assertEquals(List.of("Unknown command: frobnicate now", "Incomplete or invalid command: kick"),
                    main.messagesAt(Level.INFO));
            assertTrue(main.eventsAt(Level.INFO).stream().allMatch(event -> event.getThrowableProxy() == null),
                    "no stack");
            assertEquals(List.of(), main.messagesAt(Level.WARN));
            assertEquals(List.of(), main.messagesAt(Level.ERROR));
        }
        assertEquals(List.of("console list", "console tps", "console save"),
                events.stream().map(e -> e.get("actor") + " " + e.get("command")).toList(), "one gm_command each");
        host.disableAll();
    }

    @Test
    void theNodeDecidesWhetherAPlayerRunsAndSeesACommandOfTheHost(Env env) {
        final Instance match = env.createFlatInstance();
        final PluginHost host = host(env);
        host.enable(List.of(new Probe("mmo", match, _ -> { })), Set.of());
        nodes.put("Keeper", Set.of("synaxis.command.kick", "synaxis.command.list"));
        final TestConnection keeperConnection = env.createConnection(new GameProfile(UUID.randomUUID(), "Keeper"));
        final Collector<DeclareCommandsPacket> keeperCommands = keeperConnection.trackIncoming(DeclareCommandsPacket.class);
        final Player keeper = keeperConnection.connect(match, new Pos(0, 41, 0));
        final TestConnection ruffianConnection = env.createConnection(new GameProfile(UUID.randomUUID(), "Ruffian"));
        final Collector<DeclareCommandsPacket> ruffianCommands = ruffianConnection.trackIncoming(DeclareCommandsPacket.class);
        final Collector<DisconnectPacket> ruffianKicked = ruffianConnection.trackIncoming(DisconnectPacket.class);
        final Player ruffian = ruffianConnection.connect(match, new Pos(0, 41, 0));
        ruffian.setPermissionLevel(4);

        final Set<String> keeperSees = lastTree(keeperCommands);
        assertTrue(keeperSees.containsAll(Set.of("kick", "list")), "the completions follow the nodes: " + keeperSees);
        assertFalse(keeperSees.contains("stop"), keeperSees.toString());
        final Set<String> ruffianSees = lastTree(ruffianCommands);
        for (String name : List.of("list", "kick", "broadcast", "save", "stop", "tps"))
            assertFalse(ruffianSees.contains(name), name + " is not among the completions of a player without its node");

        assertEquals(CommandResult.Type.CANCELLED, env.process().command().execute(ruffian, "kick Keeper").getType(),
                "permission level 4 is no node");
        assertEquals(CommandResult.Type.CANCELLED, env.process().command().execute(keeper, "stop").getType());
        final Collector<TabCompletePacket> suggestions = keeperConnection.trackIncoming(TabCompletePacket.class);
        keeper.addPacketToQueue(new ClientTabCompletePacket(1, "kick Ru"));
        keeper.interpretPacketQueue();
        suggestions.assertSingle(packet -> assertEquals(List.of(new TabCompletePacket.Match("Ruffian", null)),
                packet.matches()));

        assertEquals(CommandResult.Type.SUCCESS, env.process().command().execute(keeper, "kick Nobody").getType());
        assertEquals(CommandResult.Type.SUCCESS,
                env.process().command().execute(keeper, "kick Ruffian spamming the chat").getType());
        ruffianKicked.assertSingle(packet -> assertEquals("spamming the chat", text(packet.message())));
        assertEquals(List.of("player " + keeper.getUuid() + " kick Nobody null no_target",
                        "player " + keeper.getUuid() + " kick Ruffian spamming the chat " + ruffian.getUuid() + " ok"),
                events.stream().map(e -> e.get("actor") + " " + e.get("actor_uuid") + " " + e.get("command") + " "
                        + e.get("target") + " " + e.get("result")).toList());
        host.disableAll();
    }

    @Test
    void broadcastReachesEveryPlayerAndKickWithoutAReasonIsTheVanillaOne(Env env) {
        final Instance match = env.createFlatInstance();
        final PluginHost host = host(env);
        host.enable(List.of(new Probe("mmo", match, _ -> { })), Set.of());
        final TestConnection first = env.createConnection(new GameProfile(UUID.randomUUID(), "First"));
        final Collector<SystemChatPacket> firstChat = first.trackIncoming(SystemChatPacket.class);
        first.connect(match, new Pos(0, 41, 0));
        final TestConnection second = env.createConnection(new GameProfile(UUID.randomUUID(), "Second"));
        final Collector<SystemChatPacket> secondChat = second.trackIncoming(SystemChatPacket.class);
        final Collector<DisconnectPacket> secondKicked = second.trackIncoming(DisconnectPacket.class);
        second.connect(match, new Pos(0, 41, 0));
        final RecordingConsole console = new RecordingConsole(env.process());

        env.process().command().execute(console, "broadcast restart   at noon");
        firstChat.assertSingle(packet -> assertEquals("restart   at noon", text(packet.message())));
        secondChat.assertSingle(packet -> assertEquals("restart   at noon", text(packet.message())));
        env.process().command().execute(console, "kick Second");
        secondKicked.assertSingle(packet -> assertEquals("multiplayer.disconnect.kicked", text(packet.message())));
        assertEquals(List.of("synaxis:command-broadcast 2", "synaxis:command-kicked Second"), console.answers);
        host.disableAll();
    }

    @Test
    void stopWithSecondsClosesTheGateAndStopsAfterTheCountdown(Env env) {
        host(env);
        final RecordingConsole console = new RecordingConsole(env.process());
        for (String input : List.of("stop -1", "stop soon"))
            assertEquals(CommandResult.Type.SUCCESS, env.process().command().execute(console, input).getType(), input);
        assertEquals(List.of("synaxis:command-stop-usage", "synaxis:command-stop-usage"), console.answers);
        assertEquals(ShutdownPhase.RUNNING, env.process().shutdownPhase(), "a countdown that does not parse stops nothing");

        final long start = System.nanoTime();
        assertEquals(CommandResult.Type.SUCCESS, env.process().command().execute(console, "stop 1").getType());
        assertEquals("synaxis:command-stopping 1", console.answers.getLast());
        assertEquals(CommandResult.Type.SUCCESS, env.process().command().execute(console, "stop 0").getType());
        assertEquals("synaxis:command-already-stopping", console.answers.getLast(), "a second stop changes nothing");
        await(env, () -> env.process().shutdownPhase() == ShutdownPhase.GATE_CLOSED, Duration.ofSeconds(2), "the gate");
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (env.process().shutdownPhase() != ShutdownPhase.STOPPED) {
            assertTrue(System.nanoTime() - deadline < 0, "stopped within 10 s");
            Thread.onSpinWait();
        }
        final long tookMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertTrue(tookMillis >= 1_000, "the countdown is waited for, took " + tookMillis + " ms");
        assertEquals(List.of("invalid", "invalid", "ok", "invalid"), events.stream().map(e -> e.get("result")).toList());
    }

    @Test
    void stopWhileTheEngineIsAlreadyStoppingAnswersSoAndChangesNothing(Env env) {
        host(env);
        final RecordingConsole console = new RecordingConsole(env.process());
        // What SIGTERM does, without a command of the host
        Thread.ofPlatform().start(() -> env.process().beginShutdown(Duration.ofSeconds(2)));
        await(env, () -> env.process().shutdownPhase() != ShutdownPhase.RUNNING, Duration.ofSeconds(2), "the shutdown");
        final long start = System.nanoTime();
        assertEquals(CommandResult.Type.SUCCESS, env.process().command().execute(console, "stop 0").getType());
        assertEquals(List.of("synaxis:command-already-stopping"), console.answers);
        assertEquals(List.of("invalid"), events.stream().map(e -> e.get("result")).toList());
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (env.process().shutdownPhase() != ShutdownPhase.STOPPED) {
            assertTrue(System.nanoTime() - deadline < 0, "stopped within 10 s");
            Thread.onSpinWait();
        }
        final long tookMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertTrue(tookMillis >= 1_000, "the engine's countdown is still waited for, took " + tookMillis + " ms");
    }

    @Test
    void aConsoleLineThatThrowsIsOneLogLineAndTheNextLineRuns(Env env) {
        final Instance match = env.createFlatInstance();
        final Command party = new Command("party");
        // A condition written for players only, which the console trips over
        party.setCondition((sender, _) -> ((Player) sender).isOnline());
        party.setDefaultExecutor((_, _) -> { });
        final PluginHost host = host(env);
        host.enable(List.of(new Probe("mmo", match, ctx -> ctx.commands().register(party))), Set.of());

        try (LogCapture main = LogCapture.of(Main.class)) {
            Main.readConsole(env.process(), new BufferedReader(new StringReader("mmo:party\n\nlist\n")));
            assertEquals(List.of("Console command failed: mmo:party"), main.messagesAt(Level.ERROR));
            assertEquals(ClassCastException.class.getName(),
                    main.eventsAt(Level.ERROR).getFirst().getThrowableProxy().getClassName());
        }
        assertEquals(List.of("console list"),
                events.stream().map(e -> e.get("actor") + " " + e.get("command")).toList(), "the line after it ran");
        host.disableAll();
    }

    @Test
    void aPluginCommandLivesUnderItsNamespaceAndABareNameGoesToTheFirst(Env env) {
        final Instance match = env.createFlatInstance();
        final List<String> ran = new CopyOnWriteArrayList<>();
        final Command party = new Command("party", "p");
        party.setDefaultExecutor((_, context) -> ran.add("mmo " + context.getInput()));
        final Command otherParty = new Command("party");
        otherParty.setDefaultExecutor((_, context) -> ran.add("other " + context.getInput()));
        final Command list = new Command("list");
        list.setDefaultExecutor((_, context) -> ran.add("mmo " + context.getInput()));
        final Probe mmo = new Probe("mmo", match, ctx -> {
            ctx.commands().register(party);
            ctx.commands().register(list);
        });
        final Probe other = new Probe("other", match, ctx -> ctx.commands().register(otherParty));
        final PluginHost host = host(env);
        host.enable(List.of(mmo, other), Set.of());
        final RecordingConsole console = new RecordingConsole(env.process());

        for (String input : List.of("mmo:party", "party", "p", "mmo:p", "other:party", "mmo:list", "list"))
            assertEquals(CommandResult.Type.SUCCESS, env.process().command().execute(console, input).getType(), input);
        assertEquals(List.of("mmo mmo:party", "mmo party", "mmo p", "mmo mmo:p", "other other:party", "mmo mmo:list"),
                ran, "party and p are mmo's, other only gets other:party, list stays the host's");
        assertTrue(console.answers.getLast().startsWith("synaxis:command-list "), console.answers.getLast());

        assertThrows(IllegalArgumentException.class, () -> mmo.context().commands().register(new Command("x:y")));
        assertThrows(IllegalStateException.class, () -> mmo.context().commands().register(party));
        assertFalse(mmo.context().commands().unregister(otherParty), "a plugin only takes back its own commands");
        assertEquals(CommandResult.Type.SUCCESS, env.process().command().execute(console, "other:party").getType());

        host.disableAll();
        for (String input : List.of("mmo:party", "party", "p", "mmo:p", "other:party", "mmo:list"))
            assertEquals(CommandResult.Type.UNKNOWN, env.process().command().execute(console, input).getType(), input);
        assertEquals(CommandResult.Type.SUCCESS, env.process().command().execute(console, "list").getType());
    }

    @Test
    void aThrowingPluginCommandIsOneLinePerTenSecondsAndGoneOnceDisabled(Env env) throws ClassNotFoundException {
        final Instance match = env.createFlatInstance();
        final Command party = new Command("party");
        party.setDefaultExecutor((_, _) -> {
            throw new IllegalStateException("the party is over");
        });
        final Probe mmo = new Probe("mmo", match, ctx -> ctx.commands().register(party));
        final PluginHost host = host(env);
        host.enable(List.of(mmo), Set.of());
        final TestConnection connection = env.createConnection(new GameProfile(UUID.randomUUID(), "Partier"));
        final Collector<SystemChatPacket> chat = connection.trackIncoming(SystemChatPacket.class);
        final Collector<DeclareCommandsPacket> atSpawn = connection.trackIncoming(DeclareCommandsPacket.class);
        final Player player = connection.connect(match, new Pos(0, 41, 0));
        assertTrue(lastTree(atSpawn).containsAll(Set.of("mmo:party", "party")));

        try (LogCapture host1 = LogCapture.of(PluginCommandsImpl.class);
             LogCapture engine = LogCapture.of(Class.forName("io.github.bearisbug.synaxis.command.CommandParserImpl"))) {
            for (int i = 0; i < 100; i++)
                assertEquals(CommandResult.Type.SUCCESS, env.process().command().execute(player, "mmo:party").getType());
            assertEquals(1, host1.eventsAt(Level.ERROR).size(), "one line for 100 failures within 10 s");
            assertTrue(host1.messagesAt(Level.ERROR).getFirst().startsWith(
                    "Plugin command failed: plugin_id=mmo command=mmo:party"), host1.messagesAt(Level.ERROR).getFirst());
            assertEquals(List.of(), engine.messagesAt(Level.ERROR), "the engine's line per run stays silent");
        }
        chat.assertCount(100, packet -> text(packet.message()).equals("command.failed"));

        final Collector<DeclareCommandsPacket> declared = connection.trackIncoming(DeclareCommandsPacket.class);
        host.disableAll();
        env.tick();
        final RecordingConsole console = new RecordingConsole(env.process());
        assertEquals(CommandResult.Type.UNKNOWN, env.process().command().execute(console, "mmo:party").getType());
        assertEquals(CommandResult.Type.UNKNOWN, env.process().command().execute(console, "party").getType());
        final Set<String> after = lastTree(declared);
        assertFalse(after.contains("mmo:party") || after.contains("party"), "the completions follow: " + after);
    }

    @Test
    void aCommandRegisteredOrTakenBackWhilePlayersAreOnlineReachesTheirCompletions(Env env) {
        final Instance match = env.createFlatInstance();
        final Probe mmo = new Probe("mmo", match, _ -> { });
        final PluginHost host = host(env);
        host.enable(List.of(mmo), Set.of());
        final TestConnection connection = env.createConnection(new GameProfile(UUID.randomUUID(), "Watcher"));
        Collector<DeclareCommandsPacket> declared = connection.trackIncoming(DeclareCommandsPacket.class);
        connection.connect(match, new Pos(0, 41, 0));
        assertFalse(lastTree(declared).contains("party"));

        final Command party = new Command("party");
        party.setDefaultExecutor((_, _) -> { });
        declared = connection.trackIncoming(DeclareCommandsPacket.class);
        mmo.context().commands().register(party);
        env.tick();
        assertTrue(lastTree(declared).containsAll(Set.of("mmo:party", "party")));
        declared = connection.trackIncoming(DeclareCommandsPacket.class);
        assertTrue(mmo.context().commands().unregister(party));
        env.tick();
        assertFalse(lastTree(declared).contains("party"));
        host.disableAll();
    }
}
