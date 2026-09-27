package io.github.bearisbug.synaxis.app.management;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.google.gson.JsonObject;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.command.HostCommands;
import io.github.bearisbug.synaxis.app.command.Profiler;
import io.github.bearisbug.synaxis.app.moderation.HostModeration;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.command.ConsoleSender;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.Permissions;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static io.github.bearisbug.synaxis.app.management.ManagementClient.await;
import static io.github.bearisbug.synaxis.app.management.ManagementSettingsTest.TOKEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code stop} racing SIGTERM ({@code docs/DESIGN-PROD.md} {@code API-PROD-011},
 * {@code API-PROD-022}, §16, §19 the three stop scenarios): the shutdown hook's thread is held
 * inside the engine right after its {@code stop()} took the shutdown, while the phase is still
 * {@code RUNNING}, and a console {@code stop 5} or a management {@code minecraft:server/stop} runs
 * in that window; and the ledger and the management connection close only once a stop that
 * started the shutdown is answered.
 */
@EnvTest
class StopRaceIntegrationTest {

    /** The line {@code ServerProcessImpl} logs between taking the shutdown and closing the gate. */
    private static final String STOPPING_LINE = "Stopping {} server.";

    @TempDir
    Path workingDirectory;

    private final List<Map<String, ?>> events = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> opened = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        Collections.reverse(opened);
        for (AutoCloseable closeable : opened) closeable.close();
    }

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

    /**
     * Holds one thread inside {@code ServerProcessImpl#shutdownProcess}, on the line it logs right
     * after the {@code stopping} CAS and before the phase leaves {@code RUNNING}.
     */
    private static final class HeldInsideTheEngine extends UnsynchronizedAppenderBase<ILoggingEvent>
            implements AutoCloseable {
        private final Logger engine = (Logger) LoggerFactory.getLogger("io.github.bearisbug.synaxis.ServerProcessImpl");
        private final String thread;
        final CountDownLatch inside = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        /** What the hook's {@code stop()} returned, once {@link #hook} is over. */
        final AtomicBoolean hookStarted = new AtomicBoolean();
        @Nullable Thread hook;

        HeldInsideTheEngine(String thread) {
            this.thread = thread;
            start();
            engine.addAppender(this);
        }

        @Override
        protected void append(ILoggingEvent event) {
            if (!thread.equals(event.getThreadName()) || !STOPPING_LINE.equals(event.getMessage())) return;
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            release.countDown();
            engine.detachAppender(this);
            stop();
        }
    }

    private static final class RecordingConsole extends ConsoleSender {
        final List<String> answers = new CopyOnWriteArrayList<>();

        RecordingConsole(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendMessage(Component message) {
            if (message instanceof TranslatableComponent translatable) {
                final String arguments = translatable.arguments().stream()
                        .map(argument -> ((TextComponent) argument.asComponent()).content())
                        .collect(Collectors.joining("|"));
                answers.add(arguments.isEmpty() ? translatable.key() : translatable.key() + " " + arguments);
            } else {
                answers.add(((TextComponent) message).content());
            }
        }
    }

    /** The built in commands and the management interface of a process without a database, as in Main. */
    private HostCommands commands(Env env, BiConsumer<String, Map<String, ?>> ledger) {
        final HostCommands commands = new HostCommands(env.process(), NO_NODES, PlayerStore.inMemory(env.process(),
                (_, _) -> { }), ledger, new Profiler(workingDirectory.resolve("profiles"), "test", Clock.systemUTC()));
        commands.register();
        return commands;
    }

    private ManagementServer serve(Env env, HostCommands commands, BiConsumer<String, Map<String, ?>> ledger) {
        final ManagementMethods methods = new ManagementMethods(env.process(), commands, PlayerStore.inMemory(
                env.process(), (_, _) -> { }), HostModeration.withoutDatabase(env.process()), null, "test", ledger);
        methods.serverStarted();
        final ManagementServer server = ManagementServer.serve(ManagementSettings.parse("true", "127.0.0.1:0", TOKEN),
                env.process(), methods, Duration.ofMillis(300));
        opened.add(server);
        return server;
    }

    private ManagementClient connect(ManagementServer server) throws Exception {
        final InetSocketAddress address = server.address();
        assertTrue(address != null && address.getPort() > 0, String.valueOf(address));
        final ManagementClient client = ManagementClient.connect(address, "Bearer " + TOKEN);
        opened.add(client);
        return client;
    }

    /** Runs {@code stop()} the way the shutdown hook does, held once it has taken the shutdown. */
    private HeldInsideTheEngine signal(Env env) throws InterruptedException {
        final HeldInsideTheEngine held = new HeldInsideTheEngine("SIGTERM-hook");
        opened.add(held);
        held.hook = Thread.ofPlatform().name("SIGTERM-hook").start(() -> held.hookStarted.set(env.process().stop()));
        assertTrue(held.inside.await(10, TimeUnit.SECONDS), "the hook's stop() reached the engine");
        assertEquals(ServerProcess.ShutdownPhase.RUNNING, env.process().shutdownPhase(),
                "the hook holds the shutdown, the phase has not moved yet");
        return held;
    }

    private static void assertHookStartedIt(HeldInsideTheEngine held) throws InterruptedException {
        final Thread hook = held.hook;
        assertTrue(hook != null && hook.join(Duration.ofSeconds(10)), "the hook's stop() returned");
        assertTrue(held.hookStarted.get(), "the hook's stop() says it started the shutdown");
    }

    private List<String> results() {
        return events.stream().map(event -> event.get("command") + " " + event.get("result")).toList();
    }

    @Test
    void aConsoleStopWhileASignalHoldsTheShutdownSaysItIsAlreadyStopping(Env env) throws Exception {
        commands(env, (_, attributes) -> events.add(attributes));
        final RecordingConsole console = new RecordingConsole(env.process());
        final HeldInsideTheEngine held = signal(env);

        env.process().command().execute(console, "stop 5");

        assertEquals(List.of("synaxis:command-already-stopping"), console.answers);
        assertEquals(List.of("stop 5 invalid"), results());
        final long released = System.nanoTime();
        held.release.countDown();
        await(() -> env.process().shutdownPhase() == ServerProcess.ShutdownPhase.STOPPED, "the process stops");
        final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - released);
        assertTrue(tookMillis < 5_000, "the signal's 0 s, not the 5 s of the stop: took " + tookMillis + " ms");
        assertHookStartedIt(held);
    }

    @Test
    void aManagementStopWhileASignalHoldsTheShutdownAnswersFalse(Env env) throws Exception {
        final BiConsumer<String, Map<String, ?>> ledger = (_, attributes) -> events.add(attributes);
        final ManagementClient client = connect(serve(env, commands(env, ledger), ledger));
        final HeldInsideTheEngine held = signal(env);

        final JsonObject answer = client.call(1, "minecraft:server/stop", null);

        assertFalse(answer.get("result").getAsBoolean(), answer.toString());
        assertEquals(List.of("minecraft:server/stop invalid"), results());
        held.release.countDown();
        await(() -> env.process().shutdownPhase() == ServerProcess.ShutdownPhase.STOPPED, "the process stops");
        assertHookStartedIt(held);
    }

    @Test
    void theLedgerAndTheConnectionCloseOnlyOnceTheStopThatStartedTheShutdownIsAnswered(Env env) throws Exception {
        // The ledger line of server/stop is written only once the test lets it through
        final List<String> order = new CopyOnWriteArrayList<>();
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch write = new CountDownLatch(1);
        final BiConsumer<String, Map<String, ?>> ledger = (_, attributes) -> {
            if ("minecraft:server/stop".equals(attributes.get("command"))) {
                writing.countDown();
                try {
                    write.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }
            order.add("ledger:" + attributes.get("command") + " " + attributes.get("result"));
        };
        final HostCommands commands = commands(env, ledger);
        final ManagementServer server = serve(env, commands, ledger);
        final ManagementClient client = connect(server);
        // The shutdown tasks of Main from the plugins on: the first one stands for host::disableAll
        final CountDownLatch tasksStarted = new CountDownLatch(1);
        env.process().scheduler().buildShutdownTask(tasksStarted::countDown);
        env.process().scheduler().buildShutdownTask(commands::awaitStopAnswers);
        env.process().scheduler().buildShutdownTask(() -> order.add("closed"));
        env.process().scheduler().buildShutdownTask(server::close);

        client.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"minecraft:server/stop\"}");
        assertTrue(writing.await(10, TimeUnit.SECONDS), "the call started the shutdown and is being recorded");
        assertTrue(tasksStarted.await(10, TimeUnit.SECONDS), "the sequence reached its shutdown tasks");
        // awaitStopAnswers gives up after ANSWER_WAIT (1 s): if this thread stalls for more than about
        // 900 ms between here and write.countDown(), "closed" shows up and this test goes red spuriously
        Thread.sleep(100);
        assertEquals(List.of(), order, "nothing closes while the stop is still being answered");

        write.countDown();
        final JsonObject answer = client.answer(1, null);
        assertTrue(answer.get("result").getAsBoolean(), answer.toString());
        assertEquals(1001, client.awaitClose(), "then the shutdown closes the connection");
        await(() -> env.process().shutdownPhase() == ServerProcess.ShutdownPhase.STOPPED, "the process stops");
        assertEquals(List.of("ledger:minecraft:server/stop ok", "closed"), order);
    }
}
