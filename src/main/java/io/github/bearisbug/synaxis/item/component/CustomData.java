package io.github.bearisbug.synaxis.item.component;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.tag.Tag;
import io.github.bearisbug.synaxis.tag.TagNbtAccess;
import io.github.bearisbug.synaxis.tag.TagReadable;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

public record CustomData(CompoundBinaryTag nbt) implements TagReadable {
    public static final CustomData EMPTY = new CustomData(CompoundBinaryTag.empty());

    public static final NetworkBuffer.Type<CustomData> NETWORK_TYPE = NetworkBuffer.UNTRUSTED_NBT_COMPOUND
            .transform(CustomData::new, CustomData::nbt);

    public static final Codec<CustomData> CODEC = Codec.NBT_COMPOUND
            .transform(CustomData::new, CustomData::nbt);

    @Override
    public <T> @UnknownNullability T getTag(Tag<T> tag) {
        return TagNbtAccess.read(nbt, tag);
    }

    /**
     * Sets a tag in this NBT. A value that writes no NBT, which is every value of a {@link Tag#Transient(String)}
     * tag, removes the key as a null value does.
     */
    public <T> CustomData withTag(Tag<T> tag, @Nullable T value) {
        return new CustomData(TagNbtAccess.write(nbt, tag, value));
    }
}
