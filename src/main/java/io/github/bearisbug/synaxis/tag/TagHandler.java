package io.github.bearisbug.synaxis.tag;

import net.kyori.adventure.nbt.CompoundBinaryTag;

/**
 * Represents an element which can read and write {@link Tag tags}.
 */
public interface TagHandler extends TagReadable, TagWritable {

    /**
     * Creates a readable copy of this handler.
     * <p>
     * Similar to {@link #asCompound()} with the advantage that cached objects
     * and adaptive optimizations may be reused.
     *
     * @return a copy of this handler
     */
    TagReadable readableCopy();

    /**
     * Creates a copy of this handler.
     * <p>
     * Similar to {@link #fromCompound(CompoundBinaryTag)} using {@link #asCompound()}
     * with the advantage that cached objects and adaptive optimizations may be reused.
     *
     * @return a copy of this handler
     */
    TagHandler copy();

    /**
     * Updates the content of this handler.
     * <p>
     * Can be used as a clearing method with {@link CompoundBinaryTag#empty()}.
     * <p>
     * <b>Must not be used with untrusted data.</b> Like {@link #fromCompound(CompoundBinaryTag)}, it registers every
     * key of the compound in a process wide, never shrinking index, so a compound coming from a client can grow that
     * index without bound. Read client NBT key by key instead, the way the item components do.
     *
     * @param compound the new content of this handler
     */
    void updateContent(CompoundBinaryTag compound);

    /**
     * Converts the content of this handler into a {@link CompoundBinaryTag}.
     *
     * @return a nbt compound representation of this handler
     */
    CompoundBinaryTag asCompound();

    static TagHandler newHandler() {
        return new TagHandlerImpl();
    }

    /**
     * Copy the content of the given {@link CompoundBinaryTag} into a new {@link TagHandler}.
     * <p>
     * <b>Must not be used with untrusted data.</b> Every key of the compound is registered in a
     * process wide, never shrinking index, so a compound coming from a client can grow that index
     * without bound (audit F49). Read client NBT key by key instead, the way the item components do.
     *
     * @param compound the compound to read tags from
     * @return a new tag handler with the content of the given compound
     */
    static TagHandler fromCompound(CompoundBinaryTag compound) {
        return TagHandlerImpl.fromCompound(compound);
    }
}
