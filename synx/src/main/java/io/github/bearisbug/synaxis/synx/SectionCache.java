package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.registry.Registries;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * The decoded section palettes of handle chunks ({@code docs/DESIGN-M3.md} API-M3-005 读方块), one
 * per loader, least recently used first out once the decoded palettes pass {@code section_cache_bytes}.
 * Thread safe; two threads missing the same key may both decode it, the first to finish is kept.
 * <p>
 * A chunk keeps the {@link Entry} it took, weakly, and reads through it without the lock of the
 * cache (tick threads would otherwise queue on it for every block they read); such a read marks the
 * entry, and eviction passes a marked entry over once instead of taking it, which stands in for the
 * reordering those reads do not do. The weak reference keeps nothing alive: an evicted entry is read
 * until the garbage collector takes it, then the chunk asks the cache again.
 * <p>
 * A key names content that never changes, so nothing is ever invalidated: a record of a region file
 * by its chunk, an overlay record by segment and offset (segment numbers are not reused within a
 * process), an encoding of the pending table by −1 and its sequence number. The address of a mapping
 * would not do: once the garbage collector unmaps an overlay segment, the address can come back for
 * another one.
 */
final class SectionCache {
    /**
     * @param segment the overlay segment number, −1 for a pending encoding, {@link #REGION_FILE} for a
     *                region file
     * @param offset  the offset in the segment, the sequence number of a pending encoding, or
     *                {@link CoordConversion#chunkIndex} of a region file chunk
     */
    record Key(int segment, long offset) {
        static final int REGION_FILE = Integer.MIN_VALUE;

        static Key regionFile(int chunkX, int chunkZ) {
            return new Key(REGION_FILE, CoordConversion.chunkIndex(chunkX, chunkZ));
        }

        /** The file the record of this key is mapped from, for the messages. */
        String file() {
            if (segment != REGION_FILE) return "the overlay segment " + OverlaySegment.fileName(segment);
            final int chunkX = CoordConversion.chunkIndexGetX(offset), chunkZ = CoordConversion.chunkIndexGetZ(offset);
            return "chunk " + chunkX + "," + chunkZ + ": the region file "
                    + SynxFormat.regionFileName(SynxFormat.region(chunkX), SynxFormat.region(chunkZ));
        }
    }

    /** The palettes of one key, and whether a chunk has read them since the last eviction pass. */
    static final class Entry {
        private final Key key;
        private final SynxPacketDecoder.Sections sections;
        private final long bytes;
        private volatile boolean referenced;

        private Entry(Key key, SynxPacketDecoder.Sections sections, long bytes) {
            this.key = key;
            this.sections = sections;
            this.bytes = bytes;
        }

        Key key() {
            return key;
        }

        /** The palettes, marking the entry as read. */
        SynxPacketDecoder.Sections sections() {
            if (!referenced) this.referenced = true;
            return sections;
        }
    }

    private final long maxBytes;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(256, 0.75f, true);
    private long bytes;

    SectionCache(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /**
     * The entry of {@code framed}, from the cache or decoded into it.
     *
     * @throws IllegalArgumentException if {@code framed} is not a chunk packet of {@code sectionCount} sections
     * @throws IllegalStateException    if the mapping {@code framed} is a view of faulted while it was read: the
     *                                  file was cut short under the shard ({@code API-M3-005} 映射读取出错)
     */
    Entry get(Key key, MemorySegment framed, Registries registries, int sectionCount) {
        synchronized (this) {
            final Entry entry = entries.get(key);
            if (entry != null) return entry;
        }
        final SynxPacketDecoder.Sections decoded;
        try {
            decoded = SynxPacketDecoder.sections(framed, registries, sectionCount);
        } catch (InternalError e) {
            throw new IllegalStateException(key.file() + " faulted on read", e);
        }
        final Entry inserted = new Entry(key, decoded, size(decoded));
        synchronized (this) {
            final Entry raced = entries.get(key);
            if (raced != null) return raced;
            entries.put(key, inserted);
            this.bytes += inserted.bytes;
            evict(inserted);
        }
        return inserted;
    }

    /**
     * Takes the least recently used entries out until the cache is within its budget again, all but
     * {@code keep}. The first pass spares an entry read through a chunk since the pass before, and
     * clears the mark; the second takes what it has to.
     */
    private void evict(Entry keep) {
        for (int pass = 0; pass < 2 && this.bytes > maxBytes; pass++) {
            final Iterator<Entry> eldest = entries.values().iterator();
            while (this.bytes > maxBytes && eldest.hasNext()) {
                final Entry out = eldest.next();
                if (out == keep) continue;
                if (pass == 0 && out.referenced) {
                    out.referenced = false;
                    continue;
                }
                this.bytes -= out.bytes;
                eldest.remove();
            }
        }
    }

    synchronized long bytes() {
        return bytes;
    }

    private static long size(SynxPacketDecoder.Sections sections) {
        long size = 0;
        for (Palette palette : sections.blocks()) size += size(palette, Palette.BLOCK_PALETTE_MAX_BITS);
        for (Palette palette : sections.biomes()) size += size(palette, Palette.BIOME_PALETTE_MAX_BITS);
        return size;
    }

    /**
     * What a decoded palette holds: its value array, and for an indirect palette the largest list of
     * values its width allows; 32 bytes for the object.
     */
    private static long size(Palette palette, int maxIndirectBits) {
        final int bits = palette.bitsPerEntry();
        if (bits == 0) return 32;
        final int perLong = 64 / bits;
        final int longs = (palette.maxSize() + perLong - 1) / perLong;
        final long values = bits <= maxIndirectBits ? (1L << bits) * Integer.BYTES : 0;
        return 32 + (long) longs * Long.BYTES + values;
    }
}
