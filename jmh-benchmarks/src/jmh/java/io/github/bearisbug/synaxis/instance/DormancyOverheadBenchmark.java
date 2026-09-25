package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.DormancyPolicy;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.testing.Env;
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

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * The two costs the dormancy mechanism adds to a tick ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-019}, §15「实体休眠」), apart from the rest of the tick:
 * <ul>
 *     <li>{@link #entityTick()}: one entity's tick, without a policy and with one that always answers 1;
 *     the difference is what every entity with a policy pays per tick, the distance to the nearest of
 *     {@code players} players every 5 ticks included</li>
 *     <li>{@link #snapshot()}: the player positions an instance takes once per tick</li>
 * </ul>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
public class DormancyOverheadBenchmark {

    @Param({"none", "awake"})
    public String policy;

    @Param("200")
    public int players;

    private Env env;
    private Instance instance;
    private Entity entity;

    @Setup(Level.Trial)
    public void setup() {
        this.env = Env.create(settings -> settings);
        this.instance = env.createFlatInstance();
        final Random random = new Random(20260925);
        for (int i = 0; i < players; i++) {
            final double angle = random.nextDouble() * 2 * Math.PI, radius = 64 * Math.sqrt(random.nextDouble());
            env.createPlayer(instance, new Pos(radius * Math.cos(angle), 40, radius * Math.sin(angle)));
        }
        this.entity = new Entity(EntityType.ZOMBIE);
        entity.setNoGravity(true);
        entity.setInstance(instance, new Pos(200.5, 42, 200.5)).join();
        final DormancyPolicy awake = (_, _) -> 1;
        entity.setDormancyPolicy(policy.equals("awake") ? awake : null);
        // The instance takes the positions from the tick after an entity asked for them
        instance.playerPositions();
        env.process().ticker().tick(System.nanoTime());
        if (instance.playerPositions() == null) throw new IllegalStateException("the instance took no player positions");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        env.process().stop();
    }

    @Benchmark
    public void entityTick() {
        entity.tick(0);
    }

    @Benchmark
    public PlayerPositions snapshot() {
        return PlayerPositions.of(instance.getPlayers());
    }
}
