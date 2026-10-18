package io.github.bearisbug.synaxis.tag;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.util.function.UnaryOperator;

/**
 * Represents an element which can write {@link Tag tags}.
 */
public interface TagWritable extends TagReadable {

    /**
     * Writes the specified type.
     *
     * @param tag   the tag to write
     * @param value the tag value, null to remove
     * @param <T>   the tag type
     */
    <T> void setTag(Tag<T> tag, @Nullable T value);

    default void removeTag(Tag<?> tag) {
        setTag(tag, null);
    }

    /**
     * Reads the current value, and then write the new one.
     *
     * @param tag   the tag to write
     * @param value the tag value, null to remove
     * @param <T>   the tag type
     * @return the previous tag value; when nothing was written, {@code tag.createDefault()}
     */
    <T> @Nullable T getAndSetTag(Tag<T> tag, @Nullable T value);

    /**
     * Replaces the value with what {@code value} returns for the current one, null removing the tag.
     *
     * @param tag   the tag to update
     * @param value the operator; when nothing was written it gets {@code tag.createDefault()}
     * @param <T>   the tag type
     */
    <T> void updateTag(Tag<T> tag,
                       UnaryOperator<@UnknownNullability T> value);

    /**
     * Same as {@link #updateTag(Tag, UnaryOperator)}, returning the new value.
     *
     * @param tag   the tag to update
     * @param value the operator; when nothing was written it gets {@code tag.createDefault()}
     * @param <T>   the tag type
     * @return the value the operator returned
     */
    <T> @UnknownNullability T updateAndGetTag(Tag<T> tag,
                                              UnaryOperator<@UnknownNullability T> value);

    /**
     * Same as {@link #updateTag(Tag, UnaryOperator)}, returning the previous value.
     *
     * @param tag   the tag to update
     * @param value the operator; when nothing was written it gets {@code tag.createDefault()}
     * @param <T>   the tag type
     * @return the previous value; when nothing was written, {@code tag.createDefault()}
     */
    <T> @UnknownNullability T getAndUpdateTag(Tag<T> tag,
                                              UnaryOperator<@UnknownNullability T> value);
}
