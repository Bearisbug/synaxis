package io.github.bearisbug.synaxis.synx;

import net.kyori.adventure.nbt.CompoundBinaryTag;

/**
 * One block entity of segment ③, with its complete NBT (client tags included) and its handler.
 *
 * @param sectionY the section, {@code y >> 4}, an {@code i8}
 * @param index    the block inside the section, {@code ((y & 15) × 16 + z) × 16 + x}
 * @param handler  the {@code BlockHandler} namespace, empty when the block has none
 * @param nbt      the full NBT
 */
public record SynxBlockEntity(int sectionY, int index, String handler, CompoundBinaryTag nbt) {
    public SynxBlockEntity {
        if (sectionY < Byte.MIN_VALUE || sectionY > Byte.MAX_VALUE) {
            throw new IllegalArgumentException("section y is an i8: " + sectionY);
        }
        if (index < 0 || index >= 4096) throw new IllegalArgumentException("block index out of the section: " + index);
        if (SynxDigests.utf8(handler).length > 0xFF) throw new IllegalArgumentException("handler longer than 255 bytes");
    }

    public static int index(int x, int y, int z) {
        return ((y & 15) * 16 + (z & 15)) * 16 + (x & 15);
    }
}
