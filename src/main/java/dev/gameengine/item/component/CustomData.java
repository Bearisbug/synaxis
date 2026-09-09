package dev.gameengine.item.component;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import dev.gameengine.codec.Codec;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.tag.Tag;
import dev.gameengine.tag.TagHandler;
import dev.gameengine.tag.TagReadable;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

public record CustomData(CompoundBinaryTag nbt) implements TagReadable {
    public static final CustomData EMPTY = new CustomData(CompoundBinaryTag.empty());

    public static final NetworkBuffer.Type<CustomData> NETWORK_TYPE = NetworkBuffer.NBT_COMPOUND
            .transform(CustomData::new, CustomData::nbt);

    public static final Codec<CustomData> CODEC = Codec.NBT_COMPOUND
            .transform(CustomData::new, CustomData::nbt);

    @Override
    public <T> @UnknownNullability T getTag(Tag<T> tag) {
        final TagHandler tagHandler = TagHandler.fromCompound(nbt);
        return tagHandler.getTag(tag);
    }

    public <T> CustomData withTag(Tag<T> tag, @Nullable T value) {
        TagHandler tagHandler = TagHandler.fromCompound(nbt);
        tagHandler.setTag(tag, value);
        return new CustomData(tagHandler.asCompound());
    }
}
