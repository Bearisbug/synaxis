package io.github.bearisbug.synaxis.synx;

/**
 * What {@link OverlayChunk#encodeOverlay} made of a chunk: segments ① to ③ of its current content,
 * segment ④ empty ({@code docs/DESIGN-M3.md} API-M3-005).
 *
 * @param generation the {@code generation} the content was read at
 */
public record OverlayEncoding(long generation, SynxChunkRecord record) {
}
