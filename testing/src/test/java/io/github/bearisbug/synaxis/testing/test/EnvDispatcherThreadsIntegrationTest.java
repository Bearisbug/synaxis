package io.github.bearisbug.synaxis.testing.test;

import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The multi-threaded CI round (REQ-PROD-019) only means something if every {@link Env} really runs
 * with the thread count handed to the JVM; one tick thread is the engine default.
 */
@EnvTest
public class EnvDispatcherThreadsIntegrationTest {

    @Test
    void envRunsTheDispatcherThreadsOfTheJvm(Env env) {
        final int threads = env.process().dispatcher().threads().size();
        // A build that stops handing the property to the test JVM would still pass here, with one
        // thread; the CI job greps this line out of the test report to catch that
        System.out.println("env dispatcher threads: " + threads);
        assertEquals(Integer.getInteger("synaxis.dispatcher-threads", 1), threads);
    }
}
