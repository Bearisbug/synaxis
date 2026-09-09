package net.minestom.server;

import net.minestom.server.thread.TickThread;
import net.minestom.testing.TestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class ServerProcessTest {

    @Test
    public void registries() {
        var process = MinecraftServer.updateProcess();
        assertSame(process.registries(), MinecraftServer.getRegistries());
    }

    @Test
    public void init() {
        // These like to fail on github actions
        assumeTrue(System.getenv("GITHUB_ACTIONS") == null);

        int port = TestUtils.findFreePort();
        AtomicReference<ServerProcess> process = new AtomicReference<>();
        assertDoesNotThrow(() -> process.set(MinecraftServer.updateProcess()));
        assertDoesNotThrow(() -> process.get().start(new InetSocketAddress("localhost", port)));
        assertThrows(Exception.class, () -> process.get().start(new InetSocketAddress("localhost", port)));
        assertDoesNotThrow(() -> process.get().stop());
    }

    @Test
    public void tick() {
        // These like to fail on github actions
        assumeTrue(System.getenv("GITHUB_ACTIONS") == null);

        var process = MinecraftServer.updateProcess();
        process.start(new InetSocketAddress("localhost", TestUtils.findFreePort()));
        var ticker = process.ticker();
        assertDoesNotThrow(() -> ticker.tick(System.nanoTime()));
        assertDoesNotThrow(process::stop);
    }

    @Test
    @Timeout(30)
    public void stopFromTickThread() throws InterruptedException {
        // Stopping from a tick thread must hand the blocking shutdown over to another thread (#2907)
        var process = MinecraftServer.updateProcess();
        CountDownLatch returned = new CountDownLatch(1);
        CountDownLatch shutdownTaskRan = new CountDownLatch(1);
        process.scheduler().buildShutdownTask(shutdownTaskRan::countDown);
        Thread thread = new TickThread("Ms-Tick-stop-test") {
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
