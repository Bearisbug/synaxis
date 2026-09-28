package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.instance.InstanceContainer;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The runtime light of a map instance ({@code docs/DESIGN-M3.md} {@code API-M3-005} 运行时光照,
 * {@code ADR-M3-002}, {@code ADR-M3-019}).
 */
public final class SynxRelight {
    private final SynxChunkLoader loader;
    private final InstanceContainer instance;
    private final @Nullable Executor computeExecutor;

    SynxRelight(SynxChunkLoader loader, InstanceContainer instance, @Nullable Executor computeExecutor) {
        this.loader = loader;
        this.instance = instance;
        this.computeExecutor = computeExecutor;
    }

    /**
     * Installs the results at the end of the tick, on the tick scheduler thread.
     *
     * @return the target entries it handled
     */
    public int installPhase() {
        return 0;
    }

    /**
     * The shutdown step {@code relight}, shaped as {@code ShutdownSequence.Step#run}.
     *
     * @return completes once nothing is pending any more, or at the deadline
     */
    public CompletableFuture<?> drain(Duration remaining, boolean tickAlive) {
        return CompletableFuture.completedFuture(null);
    }

    /** The counters; reading resets the window values. */
    public RelightStats stats() {
        return new RelightStats(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** {@code relight_pending} of a chunk position. */
    int pending(int chunkX, int chunkZ) {
        return 0;
    }

    /** The centers queued or running, without touching the window values of {@link #stats()}. */
    int pendingCenters() {
        return 0;
    }

    SynxChunkLoader loader() {
        return loader;
    }

    InstanceContainer instance() {
        return instance;
    }

    @Nullable Executor computeExecutor() {
        return computeExecutor;
    }
}
