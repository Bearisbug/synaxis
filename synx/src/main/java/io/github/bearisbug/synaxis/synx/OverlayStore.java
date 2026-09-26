package io.github.bearisbug.synaxis.synx;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * The overlay store of a shard ({@code docs/DESIGN-M3.md} {@code API-M3-007}): the append only
 * segment files of the chunks the game changed, per channel and map release.
 * <p>
 * The M3-C step 0 contract: the signatures the shard host builds against. The overlay track
 * replaces the bodies.
 */
@SuppressWarnings("DoNotCallSuggester") // the bodies of the skeleton, replaced by the overlay track
public final class OverlayStore implements OverlayIndex, OverlayTracker {

    private OverlayStore() {
    }

    /**
     * Opens the store of {@code manifest}'s release under {@code overlayRoot}, dropping the directories
     * of other releases, and rebuilds the index from the segment files.
     *
     * @param settings what an overlay record is encoded with
     * @throws IOException   if the directory cannot be created or read
     * @throws SynxException if a segment header does not belong to the release
     */
    public static OverlayStore open(Path overlayRoot, SynxManifest manifest, SynxSettings settings,
                                    OverlayOptions options) throws IOException, SynxException {
        throw new UnsupportedOperationException("OverlayStore is the M3-C step 0 skeleton");
    }

    /** The write phase, once a tick at the end of the tick, on the tick scheduling thread. */
    public void tickEnd() {
        throw new UnsupportedOperationException("OverlayStore is the M3-C step 0 skeleton");
    }

    /**
     * The final flush of the shutdown, with the shape of {@code ShutdownSequence.Step#run}.
     *
     * @param remaining what is left of the save budget, capped to 2 s
     * @param tickAlive true to hand the flush to the next write phase, false to run it on this thread
     * @return completes once the flush is over or its budget ran out
     */
    public CompletableFuture<?> finalFlush(Duration remaining, boolean tickAlive) {
        throw new UnsupportedOperationException("OverlayStore is the M3-C step 0 skeleton");
    }

    /** The counters; the two maxima are cleared by reading them. */
    public OverlayStats stats() {
        throw new UnsupportedOperationException("OverlayStore is the M3-C step 0 skeleton");
    }

    @Override
    public @Nullable OverlayHit find(int chunkX, int chunkZ) {
        throw new UnsupportedOperationException("OverlayStore is the M3-C step 0 skeleton");
    }

    @Override
    public void materialized(OverlayChunk chunk) {
        throw new UnsupportedOperationException("OverlayStore is the M3-C step 0 skeleton");
    }

    @Override
    public void modified(OverlayChunk chunk) {
        throw new UnsupportedOperationException("OverlayStore is the M3-C step 0 skeleton");
    }

    @Override
    public void unloadRequested(OverlayChunk chunk) {
        throw new UnsupportedOperationException("OverlayStore is the M3-C step 0 skeleton");
    }

    @Override
    public void unloaded(OverlayChunk chunk) {
        throw new UnsupportedOperationException("OverlayStore is the M3-C step 0 skeleton");
    }
}
