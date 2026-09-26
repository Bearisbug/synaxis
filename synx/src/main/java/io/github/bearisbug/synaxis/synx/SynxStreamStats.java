package io.github.bearisbug.synaxis.synx;

/**
 * The counters of {@code SynxChunkLoader#stats()} ({@code docs/DESIGN-M3.md} API-M3-005, §20
 * {@code EVT:chunk_stream_stats}). The first six count since the loader was built; the last three
 * are current values.
 *
 * @param handleSent        full chunk packets handed out in handle mode
 * @param overlaySent       full chunk packets handed out in overlay or failed mode, each encoded by the engine
 * @param bytesSent         the segment ① bytes of {@code handleSent}
 * @param failed            chunks loaded in failed mode
 * @param loaded            chunks the loader built
 * @param unloaded          chunks unloaded through the loader
 * @param sectionCacheBytes what the decoded section cache holds
 * @param regionsMapped     region files mapped
 * @param bytesMapped       their total size
 */
public record SynxStreamStats(long handleSent, long overlaySent, long bytesSent, long failed, long loaded,
                              long unloaded, long sectionCacheBytes, int regionsMapped, long bytesMapped) {
}
