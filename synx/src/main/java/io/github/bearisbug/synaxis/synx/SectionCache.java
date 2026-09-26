package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.registry.Registries;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The decoded section palettes of handle chunks ({@code docs/DESIGN-M3.md} API-M3-005 读方块), one
 * per loader, least recently used first out once the decoded palettes pass {@code section_cache_bytes}.
 * Thread safe; two threads missing the same key may both decode it, the first to finish is kept.
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
    }

    private record Entry(SynxPacketDecoder.Sections sections, long bytes) {
    }

    private final long maxBytes;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(256, 0.75f, true);
    private long bytes;

    SectionCache(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /**
     * The palettes of {@code framed}, from the cache or decoded into it.
     *
     * @throws IllegalArgumentException if {@code framed} is not a chunk packet of {@code sectionCount} sections
     */
    SynxPacketDecoder.Sections get(Key key, MemorySegment framed, Registries registries, int sectionCount) {
        synchronized (this) {
            final Entry entry = entries.get(key);
            if (entry != null) return entry.sections();
        }
        final SynxPacketDecoder.Sections decoded = SynxPacketDecoder.sections(framed, registries, sectionCount);
        final long size = size(decoded);
        synchronized (this) {
            final Entry raced = entries.get(key);
            if (raced != null) return raced.sections();
            entries.put(key, new Entry(decoded, size));
            this.bytes += size;
            final Iterator<Map.Entry<Key, Entry>> eldest = entries.entrySet().iterator();
            while (this.bytes > maxBytes && entries.size() > 1) {
                final Map.Entry<Key, Entry> out = eldest.next();
                if (out.getKey().equals(key)) continue;
                this.bytes -= out.getValue().bytes();
                eldest.remove();
            }
        }
        return decoded;
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
