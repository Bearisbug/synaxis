package io.github.bearisbug.synaxis.testing.test;

import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.time.Duration;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class EnvExceptionHandlerIntegrationTest {

    /**
     * Tick threads hand their failures to the exception handler from inside a catch block, and
     * their run loop only catches {@link Exception}, so a handler throwing an {@link Error} kills
     * the thread before it counts its tick latch down and every later tick blocks forever. The
     * fixture handler therefore records the failure and reports it on the test thread.
     */
    @Test
    void exceptionsAreReportedWithoutThrowingBackIntoTheEngine(Env env) {
        var thrown = new IllegalStateException("server side failure");
        assertDoesNotThrow(() -> env.process().exception().handleException(thrown));

        var failure = assertThrows(AssertionFailedError.class, env::tick);
        assertSame(thrown, failure.getCause());
    }

    /**
     * {@link Env#tickWhile} ticks through {@link Env#tick()}: what the server threw while a test
     * waits fails it at that tick, rather than the wait running out on a condition nothing changes
     * any more, or running for ever without a timeout.
     */
    @Test
    void tickWhileReportsWhatTheServerThrewAtTheFirstTick(Env env) {
        var thrown = new IllegalStateException("server side failure");
        env.process().exception().handleException(thrown);

        var failure = assertThrows(AssertionFailedError.class, () -> env.tickWhile(() -> true, Duration.ofSeconds(1)));
        assertSame(thrown, failure.getCause());
    }

    /**
     * An environment of its own is closed like the injected one: what the server threw fails the
     * close, the first failure with the later ones suppressed, and the process stops either way.
     */
    @Test
    void closingAnEnvironmentOfItsOwnReportsWhatTheServerThrew() {
        var first = new IllegalStateException("first");
        var second = new IllegalStateException("second");
        var env = Env.create(UnaryOperator.identity());
        env.process().exception().handleException(first);
        env.process().exception().handleException(second);

        var failure = assertThrows(AssertionFailedError.class, env::close);
        assertSame(first, failure.getCause());
        assertArrayEquals(new Throwable[]{second}, first.getSuppressed());
        var alive = env.process().dispatcher().threads().stream().filter(Thread::isAlive).toList();
        assertTrue(alive.isEmpty(), "the close failed and still stopped the tick threads: " + alive);
    }
}
