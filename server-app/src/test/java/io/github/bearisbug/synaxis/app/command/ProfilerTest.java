package io.github.bearisbug.synaxis.app.command;

import jdk.jfr.Configuration;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What goes into a recording ({@code docs/DESIGN-PROD.md} {@code API-PROD-011}): the JDK's
 * {@code profile} settings, but not the environment of the process, even while another recording
 * with the JDK's own settings runs.
 */
class ProfilerTest {

    private static final String ENVIRONMENT = "jdk.InitialEnvironmentVariable";

    @TempDir
    Path directory;

    private static long count(List<RecordedEvent> events, String name) {
        return events.stream().filter(event -> name.equals(event.getEventType().getName())).count();
    }

    @Test
    void aRecordingLeavesTheEnvironmentOfTheProcessOut() throws Exception {
        final Profiler.Started started = assertInstanceOf(Profiler.Started.class,
                new Profiler(directory, "test", Clock.systemUTC()).start(Profiler.MIN_SECONDS));
        final Recording running = FlightRecorder.getFlightRecorder().getRecordings().stream()
                .filter(recording -> recording.getName().equals("synaxis-profile")).findFirst().orElseThrow();
        assertEquals("false", running.getSettings().get(ENVIRONMENT + "#enabled"),
                "the recording does not turn the environment on");
        final List<RecordedEvent> events = RecordingFile.readAllEvents(started.written().get(10, TimeUnit.SECONDS));

        assertEquals(0, count(events, ENVIRONMENT),
                "the environment of the process, its secrets among them, is not in the file");
        assertTrue(count(events, "jdk.InitialSystemProperty") > 0, "the rest of the JDK's settings stays on");
    }

    @Test
    void anotherRecordingWithTheJdkSettingsDoesNotBringTheEnvironmentIn() throws Exception {
        // What jcmd JFR.start settings=default, -XX:StartFlightRecording and JMC run
        try (Recording other = new Recording(Configuration.getConfiguration("default"))) {
            other.start();
            final Profiler.Started started = assertInstanceOf(Profiler.Started.class,
                    new Profiler(directory, "test", Clock.systemUTC()).start(Profiler.MIN_SECONDS));
            final Path file = started.written().get(10, TimeUnit.SECONDS);
            other.stop();
            final Path otherFile = directory.resolve("other.jfr");
            other.dump(otherFile);

            assertTrue(count(RecordingFile.readAllEvents(otherFile), ENVIRONMENT) > 0,
                    "the other recording has the environment in the chunks the two share");
            final List<RecordedEvent> events = RecordingFile.readAllEvents(file);
            assertEquals(0, count(events, ENVIRONMENT),
                    "the environment the other recording turned on is not in the profile file");
            assertTrue(count(events, "jdk.InitialSystemProperty") > 0, "the rest of the file is written");
            // Byte for byte, whatever the JFR encoding: no value of a SYNAXIS_* secret of a shard, when the
            // test JVM has one (docs/TEST.md runs this with SYNAXIS_MANAGEMENT_TOKEN set)
            final String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            for (Map.Entry<String, String> variable : System.getenv().entrySet()) {
                final String key = variable.getKey();
                if (!key.startsWith("SYNAXIS_") || key.startsWith("SYNAXIS_TEST_") || variable.getValue().isEmpty()) continue;
                final byte[] value = variable.getValue().getBytes(StandardCharsets.UTF_8);
                assertFalse(bytes.contains(new String(value, StandardCharsets.ISO_8859_1)),
                        "the value of " + key + " is not in the profile file");
            }
        }
    }
}
