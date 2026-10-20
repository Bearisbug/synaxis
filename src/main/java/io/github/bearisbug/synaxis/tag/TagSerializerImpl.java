package io.github.bearisbug.synaxis.tag;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

final class TagSerializerImpl {
    public static final TagSerializer<CompoundBinaryTag> COMPOUND = new TagSerializer<>() {
        @Override
        public CompoundBinaryTag read(TagReadable reader) {
            if (reader instanceof TagHandler handler) return handler.asCompound();
            if (reader instanceof TagHandlerImpl.Node node) return node.compound();
            throw new IllegalArgumentException("TagSerializer.COMPOUND reads a TagHandler or a readableCopy(), got "
                    + reader.getClass().getName());
        }

        @Override
        public void write(TagWritable writer, CompoundBinaryTag value) {
            TagNbtSeparator.separate(value, entry -> entry.write(writer));
        }
    };

    static <T> TagSerializer<T> fromCompound(Function<CompoundBinaryTag, T> readFunc, Function<T, CompoundBinaryTag> writeFunc) {
        return new TagSerializer<>() {
            @Override
            public @Nullable T read(TagReadable reader) {
                final CompoundBinaryTag compound = COMPOUND.read(reader);
                return readFunc.apply(compound);
            }

            @Override
            public void write(TagWritable writer, T value) {
                final CompoundBinaryTag compound = writeFunc.apply(value);
                COMPOUND.write(writer, compound);
            }
        };
    }
}
