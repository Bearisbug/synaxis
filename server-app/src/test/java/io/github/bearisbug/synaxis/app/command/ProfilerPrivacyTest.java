package io.github.bearisbug.synaxis.app.command;

import io.github.bearisbug.synaxis.monitoring.EventsJFR;
import jdk.jfr.Configuration;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What else a recording leaves out, the file being handed over for analysis
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-011}, {@code REQ-PROD-034}): what players say and
 * type and where status requests come from, the command line of every process of the machine, the
 * peers of the sockets, and a system property or JVM argument whose name says it is a secret. And
 * who reads the file: its owner only.
 */
class ProfilerPrivacyTest {

    /** Turned off in the recording and filtered out of the file, like the environment. */
    private static final List<String> LEFT_OUT = List.of(EventsJFR.PLAYER_CHAT, EventsJFR.PLAYER_COMMAND,
            EventsJFR.SERVER_PING, "jdk.SystemProcess", "jdk.SocketRead", "jdk.SocketWrite");

    /**
     * 42 characters, within the 17 to 127 JFR keeps in the string pool of a chunk, which the recordings
     * sharing the chunk share: the filter must leave it out of the file too, not only the event.
     */
    private static final String POOLED_CANARY = "canary-pooled-0123456789-0123456789-abcdef";
    private static final String SECRET_PROPERTY = "synaxis.test.profile-password";
    private static final String SECRET_VALUE = "canary-4f1d";

    @TempDir
    Path directory;

    private static long count(List<RecordedEvent> events, String name) {
        return events.stream().filter(event -> name.equals(event.getEventType().getName())).count();
    }

    private static String permissions(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    private static void commitPlayerEvents() {
        final UUID player = UUID.randomUUID();
        EventsJFR.newPlayerChat(player, "canary-chat").commit();
        EventsJFR.newPlayerCommand(player, "msg bob canary-command").commit();
        EventsJFR.newServerPing("/203.0.113.7:50000").commit();
    }

    @Test
    void aRecordingLeavesPlayersTheProcessesAndTheSocketsOutAndOnlyItsOwnerReadsIt() throws Exception {
        final Path profiles = directory.resolve("profiles");
        final Profiler.Started started = assertInstanceOf(Profiler.Started.class,
                new Profiler(profiles, "test", Clock.systemUTC()).start(Profiler.MIN_SECONDS));
        final Map<String, String> settings = FlightRecorder.getFlightRecorder().getRecordings().stream()
                .filter(recording -> recording.getName().equals("synaxis-profile")).findFirst().orElseThrow()
                .getSettings();
        commitPlayerEvents();
        final Path file = started.written().get(10, TimeUnit.SECONDS);

        final List<RecordedEvent> events = RecordingFile.readAllEvents(file);
        for (String event : LEFT_OUT) assertEquals(0, count(events, event), event);
        final String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertFalse(bytes.contains("canary"), "nothing a player said or typed is in the file");
        assertFalse(bytes.contains("203.0.113.7"), "no address a status request came from is in the file");
        assertTrue(count(events, "jdk.InitialSystemProperty") > 0, "the rest of the JDK's settings stays on");
        assertEquals("rw-------", permissions(file));
        assertEquals("rwx------", permissions(profiles));
        for (String event : LEFT_OUT) assertEquals("false", settings.get(event + "#enabled"), event);
    }

    @Test
    void anotherRecordingDoesNotBringThemIn() throws Exception {
        // The JDK's own profile settings with every one of them turned on, as JMC can: an event is
        // recorded while any recording turns it on
        final Map<String, String> settings = new HashMap<>(Configuration.getConfiguration("profile").getSettings());
        for (String event : LEFT_OUT) settings.put(event + "#enabled", "true");
        try (Recording other = new Recording(settings)) {
            other.start();
            final Profiler.Started started = assertInstanceOf(Profiler.Started.class,
                    new Profiler(directory, "test", Clock.systemUTC()).start(Profiler.MIN_SECONDS));
            commitPlayerEvents();
            EventsJFR.newPlayerChat(UUID.randomUUID(), POOLED_CANARY).commit();
            final Path file = started.written().get(10, TimeUnit.SECONDS);
            other.stop();
            final Path otherFile = directory.resolve("other.jfr");
            other.dump(otherFile);

            final List<RecordedEvent> otherEvents = RecordingFile.readAllEvents(otherFile);
            assertTrue(count(otherEvents, EventsJFR.PLAYER_CHAT) > 0, "the other recording has the chat");
            assertTrue(count(otherEvents, "jdk.SystemProcess") > 0, "and the process list");
            assertTrue(new String(Files.readAllBytes(otherFile), StandardCharsets.ISO_8859_1).contains(POOLED_CANARY),
                    "the other recording's file has the long chat line");
            assertFalse(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains(POOLED_CANARY),
                    "the long chat line is nowhere in the file handed over");
            final List<RecordedEvent> events = RecordingFile.readAllEvents(file);
            for (String event : LEFT_OUT) assertEquals(0, count(events, event), event);
        }
    }

    @Test
    void aSystemPropertyNamedLikeASecretAndTheArgumentsCarryingItStayOut() throws Exception {
        final Path log = directory.resolve("fixture.log");
        final Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-D" + SECRET_PROPERTY + "=" + SECRET_VALUE,
                "-cp", System.getProperty("java.class.path"),
                ProfilerFixture.class.getName(), directory.resolve("profiles").toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the fixture ends");
        final List<String> output = Files.readAllLines(log);
        assertEquals(0, process.exitValue(), String.join("\n", output));
        final String last = output.getLast();
        assertTrue(last.startsWith(ProfilerFixture.WRITTEN), String.join("\n", output));
        final Path file = Path.of(last.substring(ProfilerFixture.WRITTEN.length()));

        final List<RecordedEvent> events = RecordingFile.readAllEvents(file);
        assertTrue(count(events, "jdk.InitialSystemProperty") > 0, "the other system properties are in the file");
        assertFalse(events.stream().anyMatch(event -> event.getEventType().getName().equals("jdk.InitialSystemProperty")
                && SECRET_PROPERTY.equals(event.getString("key"))), "the secret property is not");
        assertEquals(0, count(events, "jdk.JVMInformation"), "the JVM arguments carry it, so they are not either");
        final String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertFalse(bytes.contains(SECRET_VALUE), "its value is nowhere in the file");
    }
}
