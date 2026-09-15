package io.github.bearisbug.synaxis.testing.test;

import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
