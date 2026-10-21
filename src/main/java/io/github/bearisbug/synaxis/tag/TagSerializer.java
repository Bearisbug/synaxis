package io.github.bearisbug.synaxis.tag;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Interface used to create custom {@link Tag tags}.
 *
 * @param <T> the type to serialize
 */
public interface TagSerializer<T> {

    /**
     * Reads the custom tag from a {@link TagReadable}.
     *
     * @param reader the reader
     * @return the deserialized value, null if invalid
     */
    @Nullable T read(TagReadable reader);

    /**
     * Writes the custom tag to a {@link TagWritable}.
     *
     * @param writer the writer
     * @param value  the value to serialize
     */
    void write(TagWritable writer, T value);

    /**
     * Reads and writes the whole content as one compound. It reads from a {@link TagHandler} or from the snapshot of
     * {@link TagHandler#readableCopy()}; any other {@link TagReadable} is refused with an
     * {@link IllegalArgumentException}.
     */
    @ApiStatus.Experimental
    TagSerializer<CompoundBinaryTag> COMPOUND = TagSerializerImpl.COMPOUND;

    @ApiStatus.Experimental
    static <T> TagSerializer<T> fromCompound(Function<CompoundBinaryTag, T> reader,
                                             Function<T, CompoundBinaryTag> writer) {
        return TagSerializerImpl.fromCompound(reader, writer);
    }
}
