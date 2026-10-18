package io.github.bearisbug.synaxis;

import io.github.bearisbug.synaxis.thread.TickThread;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ServerProcess#startTicking()}: what a process created with {@link ServerProcess#of()} calls
 * after {@link ServerProcess#start} to tick, and what {@link MinecraftServer#start} does.
 */
public class ServerProcessStartTickingTest {

    @Test
    @Timeout(60)
    public void aStartedProcessTicksOnceItStartsTickingAndStopsTickingWhenItStops() {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().insideTest(true).shutdownOnSignal(false).build());
        assertThrows(IllegalStateException.class, process::startTicking, "not started yet");
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        final AtomicInteger ticks = new AtomicInteger();
        final AtomicReference<Thread> tickedOn = new AtomicReference<>();
        process.scheduler().scheduleTask(() -> {
            tickedOn.set(Thread.currentThread());
            ticks.incrementAndGet();
            return TaskSchedule.nextTick();
        }, TaskSchedule.nextTick());
        assertEquals(0, ticks.get(), "start alone does not tick");

        process.startTicking();
        await(() -> ticks.get() >= 3);
        assertThrows(IllegalStateException.class, process::startTicking, "a process starts ticking once");
        final TickSchedulerThread ticker = assertInstanceOf(TickSchedulerThread.class, tickedOn.get());

        process.stop();
        assertFalse(ticker.isAlive(), "stop() waited for the tick scheduler thread");
        for (TickThread thread : process.dispatcher().threads()) assertFalse(thread.isAlive(), thread.getName());
    }

    @Test
    @Timeout(60)
    public void theLauncherStartsTheProcessAndItsTicking() {
        final MinecraftServer server = MinecraftServer.init();
        final AtomicInteger ticks = new AtomicInteger();
        server.process().scheduler().scheduleTask(() -> {
            ticks.incrementAndGet();
            return TaskSchedule.nextTick();
        }, TaskSchedule.nextTick());
        server.start(InetAddress.getLoopbackAddress().getHostAddress(), 0);
        try {
            await(() -> ticks.get() >= 3);
            assertThrows(IllegalStateException.class, server.process()::startTicking);
        } finally {
            server.stop();
        }
    }

    private static void await(BooleanSupplier condition) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) throw new AssertionError("condition not met in 10 s");
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }
}
