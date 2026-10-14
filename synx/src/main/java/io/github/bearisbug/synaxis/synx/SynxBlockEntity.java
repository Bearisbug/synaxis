package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;

import java.util.Comparator;
import java.util.List;

/**
 * One block entity of segment ③, with its complete NBT (client tags included) and its handler.
 *
 * @param sectionY the section, {@code y >> 4}, an {@code i8}
 * @param index    the block inside the section, {@code ((y & 15) × 16 + z) × 16 + x}
 * @param handler  the {@code BlockHandler} namespace, a key, empty when the block has none
 * @param nbt      the full NBT
 */
public record SynxBlockEntity(int sectionY, int index, String handler, CompoundBinaryTag nbt) {
    /** The order of the block entity table of segment ③, by {@code section_y}, then {@code index}: the order it is written and read back in. */
    public static final Comparator<SynxBlockEntity> ORDER =
            Comparator.comparingInt(SynxBlockEntity::sectionY).thenComparingInt(SynxBlockEntity::index);
    /** The keys of a block entity record that the block carries itself: segment ① and the blocks of a shard leave them out. */
    private static final List<String> RECORD_KEYS = List.of("id", "x", "y", "z", "keepPacked");

    public SynxBlockEntity {
        if (sectionY < Byte.MIN_VALUE || sectionY > Byte.MAX_VALUE) {
            throw new IllegalArgumentException("section y is an i8: " + sectionY);
        }
        if (index < 0 || index >= 4096) throw new IllegalArgumentException("block index out of the section: " + index);
        if (SynxDigests.utf8(handler).length > 0xFF) throw new IllegalArgumentException("handler longer than 255 bytes");
        // A shard looks the handler up by its key, the engine's dummy handler throws on one that is not
        if (!handler.isEmpty() && !Key.parseable(handler)) throw new IllegalArgumentException("handler is not a key: " + handler);
    }

    public static int index(int x, int y, int z) {
        return ((y & 15) * 16 + (z & 15)) * 16 + (x & 15);
    }

    /** The block in its chunk as {@link CoordConversion#chunkBlockIndex} numbers it: {@link #index(int, int, int)} undone. */
    public int chunkBlockIndex() {
        return CoordConversion.chunkBlockIndex(index & 15, sectionY * 16 + (index >> 8), (index >> 4) & 15);
    }

    /**
     * {@code nbt} without the keys of the record that the block carries itself: the form segment ① sends the
     * client its tags from, and the one blocks of a shard and their comparison hold
     * ({@code docs/DESIGN-M3.md} API-M3-005 方块是否相同).
     */
    public static CompoundBinaryTag withoutRecordKeys(CompoundBinaryTag nbt) {
        CompoundBinaryTag without = nbt;
        for (String key : RECORD_KEYS) without = without.remove(key);
        return without;
    }
}
