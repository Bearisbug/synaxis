package io.github.bearisbug.synaxis.synx;

import org.jetbrains.annotations.Nullable;

/**
 * Where a chunk's latest overlay content is, for {@code SynxChunkLoader} to load it from
 * ({@code docs/DESIGN-M3.md} API-M3-005). {@code OverlayStore} implements it; the loader calls it
 * on the virtual threads chunks load on, so implementations are thread safe.
 */
public interface OverlayIndex {
    /**
     * Looks up the pending table first, then the index of records already {@code fsync}ed.
     *
     * @return the latest content of the chunk, null when the overlay has none
     */
    @Nullable OverlayHit find(int chunkX, int chunkZ);
}
