package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.testing.Env;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §19 「重算池在满速改方块下的积压与吞吐」 ({@code docs/DESIGN-M3.md} {@code REQ-M3-012}, §15 光照重算吞吐, M3 side
 * security review S1-04): a measurement, not a gate. Only with {@code -Dsynaxis.relight.measure=true}, which
 * {@code synx/build.gradle.kts} hands to the test JVM:
 * {@code ./gradlew :synx:test --tests '*SynxRelightBacklogTest' -Dsynaxis.relight.measure=true --rerun}.
 * <p>
 * Three recomputation threads, the 16 section high test map, the real overlay store, the install at the end
 * of every tick: 60 s of changes of block state at 107 centers a second (one client at the cap of the inbound
 * gate, S1-01), each in another chunk of the region, then 60 s as fast as the test thread changes them. One
 * line of the readings goes to the standard output; that the target entries waiting for the install stay
 * within 282 (the recomputation threads take no new center from 256 on, and each of the three adds the 9
 * targets of its 3×3 once more) is the assertion.
 */
@EnabledIfSystemProperty(named = "synaxis.relight.measure", matches = "true")
public class SynxRelightBacklogTest {
    private static final int Y = 220;
    private static final int SIDE = 32;
    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final Duration SPAN = Duration.ofSeconds(60);

    /** What one phase came to. */
    private record Reading(String phase, long changes, double seconds, double centersPerSecond, double queueMsP99,
                           double relightMsP99, int pendingPeak, double installMsMax, int backlogPeak) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "phase=%s changes=%d seconds=%.1f centers_per_s=%.1f relight_queue_p99_ms=%.1f"
                            + " relight_ms_p99=%.1f relight_pending_peak=%d install_ms_max=%.2f install_backlog_peak=%d", phase,
                    changes, seconds, centersPerSecond, queueMsP99, relightMsP99, pendingPeak, installMsMax, backlogPeak);
        }
    }

    @Test
    public void theRelightPoolUnderOneClientChangingBlocksAtTheGateRate(@TempDir Path dir) throws Exception {
        final Env env = Env.create(UnaryOperator.identity());
        try {
            measure(env, dir);
        } finally {
            env.close();
        }
    }

    private static void measure(Env env, Path dir) throws Exception {
        final SynxTestMap map = SynxTestMap.get();
        final OverlayStore store = OverlayStore.open(dir, map.manifest, map.settings(), new OverlayOptions(16_384, 10));
        final SynxChunkLoader loader = new SynxChunkLoader(SynxWorlds.mapped(map.buildDir, map.manifest, SynxTestMap.REGION),
                SynxTestMap.REGION, new SynxOptions(true, 256L << 20, 3, false), store, store);
        final Instance instance = SynxWorlds.instance(env.process(), loader);
        for (int z = 0; z < SIDE; z++) {
            for (int x = 0; x < SIDE; x++) instance.loadChunk(x, z).join();
        }
        final SynxRelight relight = loader.startRelight((InstanceContainer) instance);

        final Reading gate = run(env, instance, store, relight, "107/s", 107);
        final Reading unlimited = run(env, instance, store, relight, "unlimited", 0);
        System.out.println("relight backlog: " + gate + "; " + unlimited);
        store.finalFlush(Duration.ofSeconds(2), false).get(5, TimeUnit.SECONDS);
        for (Reading reading : new Reading[]{gate, unlimited}) {
            assertTrue(reading.backlogPeak() <= SynxRelight.INSTALL_BACKLOG + 26, "the target entries waiting for the "
                    + "install stay within 282: " + reading);
        }
    }

    /**
     * One phase of {@link #SPAN}: {@code rate} changes a second, or with 0 as many as the test thread makes
     * between two ticks, 1,024 at a time; each tick installs and runs the write phase as a shard does.
     */
    private static Reading run(Env env, Instance instance, OverlayStore store, SynxRelight relight, String phase, double rate) {
        var _ = relight.stats(); // clears the window values
        final long relightsBefore = relight.stats().relights();
        final long start = System.nanoTime();
        long changes = 0;
        int pendingPeak = 0, backlogPeak = 0;
        long nextTick = start;
        while (System.nanoTime() - start < SPAN.toNanos()) {
            final long due = rate > 0 ? (long) ((System.nanoTime() - start) / 1e9 * rate) : changes + SIDE * SIDE;
            for (; changes < due; changes++) change(instance, changes);
            env.tick();
            relight.installPhase();
            store.tickEnd();
            pendingPeak = Math.max(pendingPeak, relight.pendingCenters());
            backlogPeak = Math.max(backlogPeak, relight.installBacklog());
            if (rate > 0) {
                nextTick += TICK_NANOS;
                final long sleep = nextTick - System.nanoTime();
                if (sleep > 0) sleepNanos(sleep);
            }
        }
        final double seconds = (System.nanoTime() - start) / 1e9;
        final RelightStats stats = relight.stats();
        return new Reading(phase, changes, seconds, (stats.relights() - relightsBefore) / seconds, stats.queueMsP99(),
                stats.relightMsP99(), pendingPeak, stats.installMsMax(), backlogPeak);
    }

    /** Change {@code n}: glowstone on and off in the sky over one chunk after the other of the region. */
    private static void change(Instance instance, long n) {
        final int chunk = (int) (n % (SIDE * SIDE));
        final int chunkX = chunk % SIDE, chunkZ = chunk / SIDE;
        final Block block = (n / (SIDE * SIDE)) % 2 == 0 ? Block.GLOWSTONE : Block.AIR;
        instance.setBlock(chunkX * 16 + 8, Y, chunkZ * 16 + 8, block);
    }

    private static void sleepNanos(long nanos) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
