package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import org.jetbrains.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The overlay store of the {@code :synx} integration tests of track A ({@code docs/DESIGN-M3.md}
 * §26): an index and a tracker in memory that write nothing to disk and record what the chunks tell
 * them.
 */
final class MemoryOverlay implements OverlayIndex, OverlayTracker {
    record Event(String kind, int chunkX, int chunkZ, long generation, boolean dirty) {
    }

    private final Map<Long, OverlayHit> hits = new ConcurrentHashMap<>();
    final List<Event> events = new CopyOnWriteArrayList<>();
    final AtomicInteger finds = new AtomicInteger();
    private final AtomicInteger segments = new AtomicInteger();
    /** Runs in {@link #unloaded}, as the overlay store encodes a dirty chunk into its pending table there. */
    volatile Consumer<OverlayChunk> onUnloaded = _ -> {
    };

    @Override
    public @Nullable OverlayHit find(int chunkX, int chunkZ) {
        finds.incrementAndGet();
        return hits.get(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    /** Stores {@code encoding} as a record of a new segment and indexes it, as the write phase would. */
    OverlayHit store(OverlayEncoding encoding) {
        final OverlayHit hit = hit(encoding.record(), encoding.generation(), segments.getAndIncrement(), 256);
        hits.put(CoordConversion.chunkIndex(encoding.record().chunkX(), encoding.record().chunkZ()), hit);
        return hit;
    }

    void put(OverlayHit hit) {
        hits.put(CoordConversion.chunkIndex(hit.record().chunkX(), hit.record().chunkZ()), hit);
    }

    /** A record as {@code SynxSlot.parse} would hand it out, from the fields of {@code record}. */
    static OverlayHit hit(SynxChunkRecord record, long generation, int segment, long offset) {
        final byte[] entities = SynxEntities.encode(record.blockEntities(), record.staticEntities());
        final byte[] stored = entities.length == 0 ? entities : SynxChunkPacket.deflate(entities, -1);
        return new OverlayHit(new SynxSlot.Present(record.chunkX(), record.chunkZ(),
                MemorySegment.ofArray(record.packet()), MemorySegment.ofArray(record.navigation()),
                MemorySegment.ofArray(stored), MemorySegment.ofArray(record.columns())), generation, segment, offset);
    }

    List<Event> events(String kind) {
        return events.stream().filter(event -> event.kind().equals(kind)).toList();
    }

    @Override
    public void materialized(OverlayChunk chunk) {
        record("materialized", chunk);
    }

    @Override
    public void modified(OverlayChunk chunk) {
        record("modified", chunk);
    }

    @Override
    public void unloadRequested(OverlayChunk chunk) {
        record("unloadRequested", chunk);
    }

    @Override
    public void unloaded(OverlayChunk chunk) {
        record("unloaded", chunk);
        onUnloaded.accept(chunk);
    }

    private void record(String kind, OverlayChunk chunk) {
        events.add(new Event(kind, chunk.chunkX(), chunk.chunkZ(), chunk.generation(), chunk.dirty()));
    }
}
