package io.github.bearisbug.synaxis.synx;

import org.jetbrains.annotations.Nullable;

/**
 * A chunk as the overlay store sees it ({@code docs/DESIGN-M3.md} API-M3-005); {@code SynxChunk}
 * implements it.
 */
public interface OverlayChunk {
    int chunkX();

    int chunkZ();

    /** Counts the changes made in overlay mode; continues from the record the chunk was loaded from. */
    long generation();

    /** Whether the chunk has changes that are not written to the overlay store yet. */
    boolean dirty();

    /**
     * Encodes the current content under the chunk's write-lock, waiting for the lock until
     * {@code deadlineNanos} and at most 50 ms. Any thread.
     *
     * @param deadlineNanos a {@link System#nanoTime()} value
     * @return the encoding, null when the write-lock could not be taken in time
     */
    @Nullable OverlayEncoding encodeOverlay(SynxSettings settings, long deadlineNanos);

    /**
     * The record encoded at {@code generation} is {@code fsync}ed and indexed as {@code hit}; called
     * by the write phase afterwards. The chunk takes {@code hit} as its current source. If nothing
     * changed since the encoding it is clean again, and with {@code demote} it goes back to handle
     * mode.
     *
     * @return false if the chunk did not take {@code hit} and stays as it was, its write-lock not had
     *         in time; the overlay store takes it up again
     */
    boolean written(long generation, OverlayHit hit, boolean demote);
}
