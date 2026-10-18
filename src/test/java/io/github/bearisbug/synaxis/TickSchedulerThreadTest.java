package io.github.bearisbug.synaxis;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.thread.Acquired;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tick scheduler thread outlives what goes wrong in a tick, and names a tick that leaves an
 * {@link Acquired} locked behind it.
 */
public class TickSchedulerThreadTest {

    @Test
    @Timeout(60)
    public void anExceptionHandlerThatThrowsDoesNotEndTheTickScheduler() {
        final ServerProcess process = started();
        final TickSchedulerThread ticker = new TickSchedulerThread(process, TimeUnit.MILLISECONDS.toNanos(50),
                process.config().maxTickCatchUp());
        try {
            process.exception().setExceptionHandler(e -> {
                throw new IllegalStateException("the handler failed", e);
            });
            final AtomicInteger ticks = new AtomicInteger();
            process.scheduler().scheduleNextTick(() -> {
                throw new IllegalStateException("a task failed");
            });
            process.scheduler().scheduleTask(() -> {
                ticks.incrementAndGet();
                return TaskSchedule.nextTick();
            }, TaskSchedule.nextTick());
            ticker.start();

            await(() -> ticks.get() >= 10, "the ticks went on after the task and the handler failed");
            assertTrue(ticker.isAlive());
        } finally {
            process.exception().setExceptionHandler(null);
            process.stop();
        }
    }

    @Test
    @Timeout(60)
    public void aTickThatLeavesAnAcquiredLockedIsReported() {
        final ServerProcess process = started();
        final List<Throwable> reported = new CopyOnWriteArrayList<>();
        final AtomicReference<Acquired<? extends Entity>> leaked = new AtomicReference<>();
        final AtomicBoolean released = new AtomicBoolean();
        try {
            // On the tick scheduler thread, which holds what the task leaked: give it back
            process.exception().setExceptionHandler(e -> {
                reported.add(e);
                final Acquired<? extends Entity> acquired = leaked.get();
                if (acquired != null && released.compareAndSet(false, true)) acquired.unlock();
            });
            final InstanceContainer instance = process.instance().createInstanceContainer();
            instance.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
            final Entity entity = new Entity(EntityType.ZOMBIE);
            entity.setInstance(instance, new Pos(8, 42, 8)).join();
            new TickSchedulerThread(process, TimeUnit.MILLISECONDS.toNanos(50), process.config().maxTickCatchUp()).start();
            await(() -> entity.acquirable().assignedThread() != null, "the entity got a tick thread");

            process.scheduler().scheduleEndOfTick(() -> {
                leaked.set(entity.acquirable().lock());
                // Unless the end of this tick gave it back, the next tick would wait on it forever
                process.scheduler().scheduleNextTick(() -> {
                    if (released.compareAndSet(false, true)) leaked.get().unlock();
                });
            });

            await(() -> reported.stream().anyMatch(e -> e instanceof IllegalStateException
                    && "A tick left an Acquired locked".equals(e.getMessage())), "the leak was reported: " + reported);
            assertTrue(released.get());
        } finally {
            process.stop();
        }
    }

    private static ServerProcess started() {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().insideTest(true).shutdownOnSignal(false).build());
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        process.dispatcher().start();
        return process;
    }

    private static void await(BooleanSupplier condition, String what) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) throw new AssertionError("not within 10 s: " + what);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }
}
