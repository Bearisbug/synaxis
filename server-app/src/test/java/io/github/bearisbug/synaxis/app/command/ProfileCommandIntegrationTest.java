package io.github.bearisbug.synaxis.app.command;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.command.ConsoleSender;
import io.github.bearisbug.synaxis.command.builder.CommandResult;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.monitoring.EventsJFR;
import io.github.bearisbug.synaxis.plugin.Permissions;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import jdk.jfr.consumer.RecordingFile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built in command {@code profile [seconds]} ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-034},
 * {@code API-PROD-011}, §19 the profile scenario): a JFR recording of the given seconds, one at a
 * time, written where the answer says and readable afterwards.
 */
@EnvTest
class ProfileCommandIntegrationTest {

    @TempDir
    Path workingDirectory;

    private final List<Map<String, ?>> events = new CopyOnWriteArrayList<>();

    /** The console, recording every answer as its translate key followed by its arguments. */
    private static final class RecordingConsole extends ConsoleSender {
        final List<String> answers = new CopyOnWriteArrayList<>();

        RecordingConsole(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendMessage(Component message) {
            final TranslatableComponent translatable = (TranslatableComponent) message;
            answers.add(translatable.key() + " " + translatable.arguments().stream()
                    .map(argument -> ((TextComponent) argument.asComponent()).content())
                    .collect(Collectors.joining("|")));
        }
    }

    @Test
    void profileRecordsForTheGivenSecondsAndOnlyOneAtATime(Env env) throws Exception {
        new HostCommands(env.process(), new Permissions() {
            @Override
            public boolean has(Player player, String node) {
                return false;
            }

            @Override
            public Set<String> roles(Player player) {
                return Set.of();
            }
        }, PlayerStore.inMemory(env.process(), (_, _) -> { }), (_, attributes) -> events.add(attributes),
                new Profiler(workingDirectory.resolve("profiles"), "test", Clock.systemUTC())).register();
        final RecordingConsole console = new RecordingConsole(env.process());

        for (String input : List.of("profile 0", "profile 601", "profile soon"))
            assertEquals(CommandResult.Type.SUCCESS, env.process().command().execute(console, input).getType(), input);
        assertEquals(List.of("synaxis:command-profile-usage 1|600", "synaxis:command-profile-usage 1|600",
                "synaxis:command-profile-usage 1|600"), console.answers);
        assertFalse(Files.exists(workingDirectory.resolve("profiles")), "a length out of range records nothing");

        assertEquals(CommandResult.Type.SUCCESS, env.process().command().execute(console, "profile 1").getType());
        final String started = console.answers.getLast();
        assertTrue(started.startsWith("synaxis:command-profile-started 1|"), started);
        final Path path = Path.of(started.substring(started.indexOf('|') + 1));
        assertTrue(path.isAbsolute() && path.startsWith(workingDirectory.resolve("profiles")), path.toString());
        // Without a length it would record 60 s; one runs, so it records nothing
        assertEquals(CommandResult.Type.SUCCESS, env.process().command().execute(console, "profile").getType());
        assertEquals("synaxis:command-profile-busy " + path, console.answers.getLast());

        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!console.answers.getLast().startsWith("synaxis:command-profile-done")) {
            assertTrue(System.nanoTime() < deadline, "the recording is written within 10 s: " + console.answers);
            env.tick();
            Thread.sleep(10);
        }
        assertEquals("synaxis:command-profile-done " + path, console.answers.getLast());
        assertTrue(RecordingFile.readAllEvents(path).stream()
                        .anyMatch(event -> EventsJFR.SERVER_TICK.equals(event.getEventType().getName())),
                "the ticks of the recording are in it");
        assertEquals(List.of("profile 0 invalid", "profile 601 invalid", "profile soon invalid", "profile invalid",
                "profile 1 ok"), events.stream().map(event -> event.get("command") + " " + event.get("result")).toList());
    }

    @Test
    void aRecordingThatCannotStartIsAnsweredAsFailed(Env env) throws Exception {
        new HostCommands(env.process(), new Permissions() {
            @Override
            public boolean has(Player player, String node) {
                return false;
            }

            @Override
            public Set<String> roles(Player player) {
                return Set.of();
            }
        }, PlayerStore.inMemory(env.process(), (_, _) -> { }), (_, attributes) -> events.add(attributes),
                new Profiler(workingDirectory.resolve("profiles"), "test", Clock.systemUTC())).register();
        final RecordingConsole console = new RecordingConsole(env.process());
        // Where the recordings go is taken by a file
        Files.writeString(workingDirectory.resolve("profiles"), "in the way");

        assertEquals(CommandResult.Type.SUCCESS, env.process().command().execute(console, "profile 1").getType());
        assertEquals(1, console.answers.size(), console.answers.toString());
        assertTrue(console.answers.getFirst().startsWith("synaxis:command-profile-failed "), console.answers.getFirst());
        assertEquals(List.of("profile 1 unavailable"),
                events.stream().map(event -> event.get("command") + " " + event.get("result")).toList());
    }
}
