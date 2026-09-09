package dev.gameengine.item.component;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import dev.gameengine.codec.Codec;
import dev.gameengine.codec.StructCodec;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.tag.Tag;
import dev.gameengine.tag.TagHandler;
import dev.gameengine.tag.TagReadable;
import org.jetbrains.annotations.UnknownNullability;

public record TypedCustomData<T>(T type, CompoundBinaryTag nbt) implements TagReadable {

    public static <T> Codec<TypedCustomData<T>> codec(Codec<T> typeCodec) {
        return StructCodec.struct(
                "id", typeCodec, TypedCustomData::type,
                StructCodec.INLINE, Codec.NBT_COMPOUND, TypedCustomData::nbt,
                TypedCustomData::new
        );
    }

    public static <T> NetworkBuffer.Type<TypedCustomData<T>> networkType(NetworkBuffer.Type<T> typeNetwork) {
        return NetworkBufferTemplate.template(
                typeNetwork, TypedCustomData::type,
                NetworkBuffer.NBT_COMPOUND, TypedCustomData::nbt,
                TypedCustomData::new
        );
    }

    public TypedCustomData(T type, CompoundBinaryTag nbt) {
        this.type = type;
        this.nbt = nbt.remove("id");
    }

    @Override
    public <TT> @UnknownNullability TT getTag(Tag<TT> tag) {
        final TagHandler tagHandler = TagHandler.fromCompound(nbt);
        return tagHandler.getTag(tag);
    }

    public <TT> TypedCustomData<T> withTag(Tag<TT> tag, TT value) {
        TagHandler tagHandler = TagHandler.fromCompound(nbt);
        tagHandler.setTag(tag, value);
        return new TypedCustomData<>(type, tagHandler.asCompound());
    }
}
