package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.timer.Task;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How often a failing plugin task writes to the log ({@code docs/DESIGN-PROD.md} REQ-PROD-021,
 * API-PROD-017): through a budget, one per repeating task and one for every other task, so neither a
 * task failing every tick nor a {@code runOn} per inbound packet writes a line per run.
 */
@EnvTest
class PluginSchedulerLogBudgetIntegrationTest {

    @Test
    void aRepeatingTaskThatFailsEveryTickWritesOneLinePerBudget(Env env) {
        final PluginSchedulerImpl scheduler = new PluginSchedulerImpl("probe", env.process().scheduler());
        final AtomicInteger runs = new AtomicInteger();
        try (LogCapture log = LogCapture.of(PluginSchedulerImpl.class)) {
            final Task failing = scheduler.scheduleTask(() -> {
                runs.incrementAndGet();
                throw new IllegalStateException("the test's task fails");
            }, TaskSchedule.immediate(), TaskSchedule.nextTick());
            var _ = scheduler.scheduleTask(() -> {
                throw new IllegalStateException("the test's other task fails too");
            }, TaskSchedule.immediate(), TaskSchedule.nextTick());

            for (int i = 0; i < 100; i++) env.tick();

            assertTrue(runs.get() >= 99, "the task goes on repeating: " + runs.get() + " runs");
            assertTrue(failing.isAlive());
            final List<ILoggingEvent> errors = log.eventsAt(Level.ERROR);
            assertEquals(2, errors.size(), "one line for each repeating task: " + log.messagesAt(Level.ERROR));
            for (ILoggingEvent error : errors) {
                assertTrue(error.getFormattedMessage().startsWith("Plugin task failed: plugin_id=probe"),
                        error.getFormattedMessage());
                assertNotNull(error.getThrowableProxy(), "the first line of a budget keeps its stack");
            }
        } finally {
            scheduler.cancelAll();
        }
    }

    @Test
    void theOneShotTasksShareOneBudgetWhateverTheirNumber(Env env) {
        final PluginSchedulerImpl scheduler = new PluginSchedulerImpl("probe", env.process().scheduler());
        final Entity target = new Entity(EntityType.ZOMBIE);
        target.setInstance(env.createFlatInstance(), new Pos(0, 42, 0)).join();
        env.tick();
        final AtomicInteger runs = new AtomicInteger();
        try (LogCapture log = LogCapture.of(PluginSchedulerImpl.class)) {
            // What a plugin delivering an effect per inbound packet does
            for (int i = 0; i < 1_000; i++) {
                var _ = scheduler.runOn(target, () -> {
                    runs.incrementAndGet();
                    throw new IllegalStateException("the test's effect fails");
                }, TaskSchedule.immediate());
            }
            var _ = scheduler.scheduleTask(() -> {
                runs.incrementAndGet();
                throw new IllegalStateException("the test's one shot task fails");
            }, TaskSchedule.immediate(), TaskSchedule.stop());

            for (int i = 0; i < 3; i++) env.tick();

            assertEquals(1_001, runs.get());
            assertEquals(1, log.eventsAt(Level.ERROR).size(), "one line for all of them: " + log.eventsAt(Level.ERROR).size());
        } finally {
            scheduler.cancelAll();
        }
    }
}
