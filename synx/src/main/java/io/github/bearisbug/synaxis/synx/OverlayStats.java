package io.github.bearisbug.synaxis.synx;

/**
 * The counters of {@code OverlayStore#stats()} ({@code docs/DESIGN-M3.md} API-M3-005, §20
 * {@code EVT:chunk_stream_stats}). {@code resident} and {@code records} are current values, the
 * next four count since the store was opened, and the two maxima cover the time since the previous
 * call of {@code stats()}, which resets them.
 *
 * @param resident        {@code overlay_resident}: chunks in overlay mode
 * @param evicted         chunks the write phase sent back to handle mode
 * @param evictFailed     write phases whose appends or {@code fsync} failed
 * @param flushAppended   records appended, of every kind
 * @param flushFsyncs     group commits, one per write phase that appended
 * @param flushLagSMax    the longest time a written chunk had been dirty, in seconds
 * @param writePhaseMsMax the longest write phase, in milliseconds
 * @param records         chunks the index holds a record of
 */
public record OverlayStats(int resident, long evicted, long evictFailed, long flushAppended, long flushFsyncs,
                           double flushLagSMax, double writePhaseMsMax, long records) {
}
