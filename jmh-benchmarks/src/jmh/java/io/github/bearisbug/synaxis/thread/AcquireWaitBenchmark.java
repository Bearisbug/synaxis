package io.github.bearisbug.synaxis.thread;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * What measuring the acquisition wait costs a {@code sync} ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-016}): an element of a tick thread that is not ticking, synced from a plain thread
 * and from another tick thread holding its own lock, which is the path of an entity syncing one of
 * another cell. Nothing contends, the case of almost every acquisition.
 */
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class AcquireWaitBenchmark {
    private static final int SYNCS = 1_000_000;

    private Acquirable<int[]> element;

    @Setup(Level.Trial)
    public void setup() {
        // Never started: its lock is free the whole time
        final TickThread owner = new TickThread("Bench-Owner", Throwable::printStackTrace);
        this.element = Acquirable.unassigned(new int[1]);
        ((AcquirableImpl<int[]>) element).assign(owner);
    }

    @Benchmark
    public int plainThreadSync() {
        element.sync(value -> value[0]++);
        return element.unwrap()[0];
    }

    @Benchmark
    @OperationsPerInvocation(SYNCS)
    public void tickThreadSync() throws InterruptedException {
        final TickThread caller = new TickThread("Bench-Caller", Throwable::printStackTrace) {
            @Override
            public void run() {
                lock().lock();
                try {
                    for (int i = 0; i < SYNCS; i++) element.sync(value -> value[0]++);
                } finally {
                    lock().unlock();
                }
            }
        };
        caller.start();
        caller.join();
    }
}
