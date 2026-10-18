package io.github.bearisbug.synaxis.timer;

import io.github.bearisbug.synaxis.exception.ExceptionManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shutdown tasks of {@code docs/DESIGN-PROD.md} §11.4 {@code kicking → plugins_disabled}: run
 * once each, in the order they were registered, one that throws keeping none of the others from
 * running.
 */
public class SchedulerManagerTest {

    @Test
    public void aShutdownTaskThatThrowsIsReportedAndTheOnesAfterItStillRun() {
        final ExceptionManager exceptions = new ExceptionManager();
        final List<Throwable> reported = new CopyOnWriteArrayList<>();
        exceptions.setExceptionHandler(reported::add);
        final SchedulerManager scheduler = new SchedulerManager(exceptions);
        final List<String> ran = new CopyOnWriteArrayList<>();
        final IllegalStateException failure = new IllegalStateException("the second task failed");
        scheduler.buildShutdownTask(() -> ran.add("first"));
        scheduler.buildShutdownTask(() -> {
            ran.add("second");
            throw failure;
        });
        scheduler.buildShutdownTask(() -> ran.add("third"));

        scheduler.shutdown();

        assertEquals(List.of("first", "second", "third"), ran);
        assertEquals(List.of(failure), reported, "the task's own exception, once");

        scheduler.shutdown();
        assertEquals(List.of("first", "second", "third"), ran, "a task runs once");
    }
}
