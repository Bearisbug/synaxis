package io.github.bearisbug.synaxis;

import io.github.bearisbug.synaxis.thread.TickThread;
import io.github.bearisbug.synaxis.testing.TestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class ServerProcessTest {

    @Test
    public void registries() {
        var process = ServerProcess.of();
        assertSame(process.registries(), process.registries());
        assertNotSame(process.registries(), ServerProcess.of().registries());
    }

    @Test
    public void init() {
        // These like to fail on github actions
        assumeTrue(System.getenv("GITHUB_ACTIONS") == null);

        int port = TestUtils.findFreePort();
        AtomicReference<ServerProcess> process = new AtomicReference<>();
        assertDoesNotThrow(() -> process.set(ServerProcess.of()));
        assertDoesNotThrow(() -> process.get().start(new InetSocketAddress("localhost", port)));
        assertThrows(Exception.class, () -> process.get().start(new InetSocketAddress("localhost", port)));
        assertDoesNotThrow(() -> process.get().stop());
    }

    @Test
    public void tick() {
        // These like to fail on github actions
        assumeTrue(System.getenv("GITHUB_ACTIONS") == null);

        var process = ServerProcess.of();
        process.start(new InetSocketAddress("localhost", TestUtils.findFreePort()));
        var ticker = process.ticker();
        assertDoesNotThrow(() -> ticker.tick(System.nanoTime()));
        assertDoesNotThrow(process::stop);
    }

    @Test
    public void shutdownHookIsUnregisteredOnStop() {
        // These like to fail on github actions
        assumeTrue(System.getenv("GITHUB_ACTIONS") == null);

        // A registered hook is strongly referenced by the JVM, keeping the whole process (and its
        // world) alive for as long as the JVM runs.
        var process = ServerProcess.of(ServerConfig.builder().insideTest(true).shutdownOnSignal(true).build());
        process.start(new InetSocketAddress("localhost", TestUtils.findFreePort()));
        Thread hook = ((ServerProcessImpl) process).shutdownHook;
        assertNotNull(hook, "start() must register a shutdown hook when shutdownOnSignal is set");
        process.stop();
        assertFalse(Runtime.getRuntime().removeShutdownHook(hook), "the shutdown hook outlived the process");
    }

    @Test
    @Timeout(30)
    public void stopFromTickThread() throws InterruptedException {
        // Stopping from a tick thread must hand the blocking shutdown over to another thread (#2907)
        var process = ServerProcess.of();
        CountDownLatch returned = new CountDownLatch(1);
        CountDownLatch shutdownTaskRan = new CountDownLatch(1);
        process.scheduler().buildShutdownTask(shutdownTaskRan::countDown);
        Thread thread = new TickThread("Ms-Tick-stop-test", process.exception()::handleException) {
            @Override
            public void run() {
                process.stop();
                returned.countDown();
            }
        };
        thread.start();
        assertTrue(returned.await(5, TimeUnit.SECONDS), "stop() from a TickThread must not block the caller");
        assertTrue(shutdownTaskRan.await(5, TimeUnit.SECONDS), "the shutdown was never completed");
        thread.join();
    }
}
