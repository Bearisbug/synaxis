package io.github.bearisbug.synaxis.synx;

/**
 * What {@code SynxChunk} tells the overlay store about its overlay life ({@code docs/DESIGN-M3.md}
 * API-M3-005). {@code OverlayStore} implements it; implementations are thread safe.
 */
public interface OverlayTracker {
    /** The chunk went from handle to overlay mode; the caller holds the chunk's write-lock. */
    void materialized(OverlayChunk chunk);

    /** The chunk changed in overlay mode, after the change; the caller holds the chunk's write-lock. */
    void modified(OverlayChunk chunk);

    /**
     * The automatic unload found the chunk dirty and keeps it; the store writes it first. Called on the
     * tick scheduling thread.
     */
    void unloadRequested(OverlayChunk chunk);

    /**
     * The chunk left its instance ({@code ChunkLoader#unloadChunk}); the caller holds the instance
     * monitor, on any thread, and not the chunk's lock.
     */
    void unloaded(OverlayChunk chunk);
}
