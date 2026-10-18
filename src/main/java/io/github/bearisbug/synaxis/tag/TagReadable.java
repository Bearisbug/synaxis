package io.github.bearisbug.synaxis.tag;

import org.jetbrains.annotations.UnknownNullability;

/**
 * Represents an element which can read {@link Tag tags}.
 */
public interface TagReadable {

    /**
     * Reads the specified tag.
     *
     * @param tag the tag to read
     * @param <T> the tag type
     * @return the read tag; when nothing was written, {@code tag.createDefault()}, which is null
     * only for a tag without a default value
     */
    <T> @UnknownNullability T getTag(Tag<T> tag);

    /**
     * Returns whether {@link #getTag(Tag)} is non-null. A tag built with a default value is therefore
     * always reported present; to test whether a value was actually stored, ask with the same tag
     * built without a default.
     *
     * @param tag the tag to check
     * @return true if {@code getTag(tag)} is non-null, false otherwise
     */
    default boolean hasTag(Tag<?> tag) {
        return getTag(tag) != null;
    }
}
