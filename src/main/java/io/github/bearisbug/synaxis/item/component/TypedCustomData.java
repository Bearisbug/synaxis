package io.github.bearisbug.synaxis.item.component;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.tag.Tag;
import io.github.bearisbug.synaxis.tag.TagHandler;
import io.github.bearisbug.synaxis.tag.TagReadable;
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
                NetworkBuffer.UNTRUSTED_NBT_COMPOUND, TypedCustomData::nbt,
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
