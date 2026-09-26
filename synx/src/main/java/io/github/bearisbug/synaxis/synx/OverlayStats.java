package io.github.bearisbug.synaxis.synx;

/**
 * The counters of {@code OverlayStore#stats()} ({@code docs/DESIGN-M3.md} API-M3-005, §20
 * {@code EVT:chunk_stream_stats}).
 *
 * @param resident         {@code overlay_resident}, current
 * @param evicted          chunks evicted back to handle mode, since the store opened
 * @param evictFailed      failed writes, since the store opened
 * @param flushAppended    records appended, since the store opened
 * @param flushFsyncs      {@code fsync} calls, since the store opened
 * @param flushLagSMax     the longest a dirty chunk waited to be written, since the last call; reading clears it
 * @param writePhaseMsMax  the longest write phase, since the last call; reading clears it
 * @param records          records in the index, current
 */
public record OverlayStats(int resident, long evicted, long evictFailed, long flushAppended, long flushFsyncs,
                           double flushLagSMax, double writePhaseMsMax, long records) {
}
