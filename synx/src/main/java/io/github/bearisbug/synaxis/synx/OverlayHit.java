package io.github.bearisbug.synaxis.synx;

/**
 * One chunk record of the overlay store and where it sits ({@code docs/DESIGN-M3.md} API-M3-005).
 * The content a position names never changes: segment numbers are not reused within a process.
 *
 * @param record     the record, a view into its segment file or into the heap
 * @param generation the {@code generation} the record was encoded at
 * @param segment    the segment file number, −1 for an encoding still in the pending table
 * @param offset     the offset of the record in its segment, or the process wide sequence number of a
 *                   pending encoding
 */
public record OverlayHit(SynxSlot.Present record, long generation, int segment, long offset) {
}
