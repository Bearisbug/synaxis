package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.thread.TickThread;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * One whole server tick with {@code entities} creatures and {@code players} players, by dormancy
 * policy ({@code docs/DESIGN-PROD.md} {@code API-PROD-019}, §15「实体休眠」): the players stand in a
 * town of radius 64 blocks, the creatures are spread over 1,024 by 1,024 blocks around it, on flat
 * ground with gravity. One tick thread, the configuration of the tests.
 * <ul>
 *     <li>{@code off}: no policy, every creature runs every tick</li>
 *     <li>{@code awake}: a policy that always answers 1, so the cost of the mechanism alone: the player
 *     positions of the instance, the clock of every creature and a policy call every 5 ticks</li>
 *     <li>{@code tiered}: every 20 ticks past 32 blocks, every 100 past 64</li>
 *     <li>{@code dormant}: every 20 ticks whatever the distance</li>
 * </ul>
 * The setup checks that the creatures run as often as the policy says, and fails otherwise.
 * <p>
 * Besides the wall time of JMH, every iteration prints the CPU time a tick took on the benchmark
 * thread and the tick threads together, which a busy machine disturbs less.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class EntityDormancyBenchmark {

    @Param({"off", "awake", "tiered", "dormant"})
    public String policy;

    @Param("2000")
    public int entities;

    @Param("200")
    public int players;

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private Env env;
    private ServerProcess process;
    private long[] tickThreads;
    private long cpuNanos, ticks;

    @Setup(Level.Trial)
    public void setup() {
        this.env = Env.create(settings -> settings);
        this.process = env.process();
        final Instance instance = env.createFlatInstance();
        final Random random = new Random(20260925);
        for (int i = 0; i < players; i++) {
            final double angle = random.nextDouble() * 2 * Math.PI, radius = 64 * Math.sqrt(random.nextDouble());
            env.createPlayer(instance, new Pos(radius * Math.cos(angle), 40, radius * Math.sin(angle)));
        }
        final DormancyPolicy dormancy = switch (policy) {
            case "off" -> null;
            case "awake" -> (_, _) -> 1;
            case "tiered" -> (_, distance) -> distance > 64 ? 100 : distance > 32 ? 20 : 1;
            case "dormant" -> (_, _) -> 20;
            default -> throw new IllegalArgumentException(policy);
        };
        final List<Entity> creatures = new ArrayList<>(entities);
        for (int i = 0; i < entities; i++) {
            final Entity creature = new EntityCreature(EntityType.ZOMBIE);
            creature.setInstance(instance, new Pos(random.nextDouble() * 1024 - 512, 40, random.nextDouble() * 1024 - 512)).join();
            creature.setDormancyPolicy(dormancy);
            creatures.add(creature);
        }
        for (int i = 0; i < 100; i++) step();

        // Every creature runs every tick, once every 20 or once every 100
        final long[] before = new long[entities];
        for (int i = 0; i < entities; i++) before[i] = creatures.get(i).getAliveTicks();
        for (int i = 0; i < 100; i++) step();
        final int[] runs = new int[4];
        for (int i = 0; i < entities; i++) {
            final long ran = creatures.get(i).getAliveTicks() - before[i];
            runs[ran == 100 ? 0 : ran == 5 ? 1 : ran == 1 ? 2 : 3]++;
        }
        System.out.printf("%n%s: %d creatures ran every tick, %d every 20, %d every 100, %d otherwise%n",
                policy, runs[0], runs[1], runs[2], runs[3]);
        final boolean asSaid = switch (policy) {
            case "off", "awake" -> runs[0] == entities;
            case "dormant" -> runs[1] == entities;
            default -> runs[3] == 0 && runs[0] < entities;
        };
        if (!asSaid)
            throw new IllegalStateException(policy + ": the creatures did not run as the policy says, " + runs[0]
                    + " every tick, " + runs[1] + " every 20, " + runs[2] + " every 100, " + runs[3] + " otherwise");
        this.tickThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith(TickThread.THREAD_NAME_TICK + "-"))
                .mapToLong(Thread::threadId).toArray();
    }

    @TearDown(Level.Iteration)
    public void printCpu() {
        if (ticks > 0) System.out.printf("%ncpu %s: %.1f us/tick over %d ticks%n", policy, cpuNanos / 1e3 / ticks, ticks);
        cpuNanos = 0;
        ticks = 0;
    }

    private long cpu() {
        long nanos = THREADS.getCurrentThreadCpuTime();
        for (long thread : tickThreads) nanos += THREADS.getThreadCpuTime(thread);
        return nanos;
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        process.stop();
    }

    private void step() {
        process.ticker().tick(System.nanoTime());
    }

    @Benchmark
    public void tick() {
        final long before = cpu();
        step();
        cpuNanos += cpu() - before;
        ticks++;
    }
}
