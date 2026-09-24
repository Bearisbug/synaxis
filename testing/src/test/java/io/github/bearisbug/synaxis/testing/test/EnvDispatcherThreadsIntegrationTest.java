package io.github.bearisbug.synaxis.testing.test;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The multi-threaded CI rounds (REQ-PROD-019, REQ-PROD-020) only mean something if every {@link Env}
 * really runs with the thread count and the thread provider handed to the JVM; one tick thread and
 * the counter are the engine defaults.
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

    @Test
    void envRunsTheThreadProviderOfTheJvm(Env env) {
        final ServerConfig.ThreadProviderKind provider = env.process().config().threadProvider();
        final String name = provider.name().toLowerCase(Locale.ROOT);
        // Grepped by the grid round of the CI job, for the same reason as the thread count above
        System.out.println("env thread provider: " + name);
        assertEquals(System.getProperty("synaxis.thread-provider", "counter"), name);
        if (provider != ServerConfig.ThreadProviderKind.GRID) return;

        // Chunks (0, 0) and (15, 15) are one cell of the grid, so they tick on one thread
        final Instance instance = env.createFlatInstance();
        final Entity first = new Entity(EntityType.ZOMBIE);
        final Entity second = new Entity(EntityType.ZOMBIE);
        first.setInstance(instance, new Pos(8, 42, 8)).join();
        second.setInstance(instance, new Pos(15 * 16 + 8, 42, 15 * 16 + 8)).join();
        env.tick();
        assertNotNull(first.acquirable().assignedThread());
        assertSame(first.acquirable().assignedThread(), second.acquirable().assignedThread());
    }
}
