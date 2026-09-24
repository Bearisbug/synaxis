package io.github.bearisbug.synaxis.baker.anvil;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.Nullable;

/**
 * A block entity of a source chunk, kept whole: {@code nbt} is the source compound as read, with
 * {@code id}, the coordinates and every other tag, never filtered through a block lookup (seven 26.2
 * block entity types, {@code sign}, {@code banner}, {@code skull} among them, have no block of the same
 * name). A data marker above it adds {@code handler} and merges its SNBT over {@code nbt}.
 *
 * @param sectionY section coordinate, {@code y >> 4}
 * @param index    position inside the section, {@code ((y & 15) × 16 + (z & 15)) × 16 + (x & 15)}
 * @param handler  handler namespace from a data marker, or null
 * @param nbt      the full compound
 */
public record AnvilBlockEntity(int sectionY, int index, @Nullable String handler, CompoundBinaryTag nbt) {

    public static int index(int x, int y, int z) {
        return ((y & 15) * 16 + (z & 15)) * 16 + (x & 15);
    }

    /** Section relative x. */
    public int localX() {
        return index & 15;
    }

    /** Block y. */
    public int y() {
        return sectionY * 16 + (index >> 8);
    }

    /** Section relative z. */
    public int localZ() {
        return (index >> 4) & 15;
    }
}
