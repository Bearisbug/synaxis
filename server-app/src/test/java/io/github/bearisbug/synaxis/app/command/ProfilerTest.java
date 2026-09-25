package io.github.bearisbug.synaxis.app.command;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What goes into a recording ({@code docs/DESIGN-PROD.md} {@code API-PROD-011}): the JDK's
 * {@code profile} settings, but not the environment of the process.
 */
class ProfilerTest {

    @TempDir
    Path directory;

    private static long count(List<RecordedEvent> events, String name) {
        return events.stream().filter(event -> name.equals(event.getEventType().getName())).count();
    }

    @Test
    void aRecordingLeavesTheEnvironmentOfTheProcessOut() throws Exception {
        final Profiler.Started started = assertInstanceOf(Profiler.Started.class,
                new Profiler(directory, "test", Clock.systemUTC()).start(Profiler.MIN_SECONDS));
        final List<RecordedEvent> events = RecordingFile.readAllEvents(started.written().get(10, TimeUnit.SECONDS));

        assertEquals(0, count(events, "jdk.InitialEnvironmentVariable"),
                "the environment of the process, its secrets among them, is not in the file");
        assertTrue(count(events, "jdk.InitialSystemProperty") > 0, "the rest of the JDK's settings stays on");
    }
}
