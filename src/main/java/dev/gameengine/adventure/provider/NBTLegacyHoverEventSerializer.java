package dev.gameengine.adventure.provider;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.json.LegacyHoverEventSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.util.Codec;
import dev.gameengine.adventure.EngineAdventure;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

final class NBTLegacyHoverEventSerializer implements LegacyHoverEventSerializer {
    static final NBTLegacyHoverEventSerializer INSTANCE = new NBTLegacyHoverEventSerializer();

    private static final String ITEM_TYPE = "id", ITEM_COUNT = "Count", ITEM_TAG = "tag";
    private static final String ENTITY_TYPE = "type", ENTITY_NAME = "name", ENTITY_ID = "id";

    private NBTLegacyHoverEventSerializer() {
    }

    @Override
    public HoverEvent.ShowItem deserializeShowItem(Component input) throws IOException {
        final String raw = PlainTextComponentSerializer.plainText().serialize(input);
        // attempt the parse
        final CompoundBinaryTag contents = EngineAdventure.NBT_CODEC.decode(raw);
        final CompoundBinaryTag tag = contents.getCompound(ITEM_TAG);

        // create the event
        return HoverEvent.ShowItem.showItem(
                Key.key(contents.getString(ITEM_TYPE, "")),
                contents.getByte(ITEM_COUNT, (byte) 1),
                tag.isEmpty() ? null : BinaryTagHolder.encode(tag, EngineAdventure.NBT_CODEC)
        );
    }

    @Override
    public HoverEvent.ShowEntity deserializeShowEntity(Component input, Codec.Decoder<Component, String, ? extends RuntimeException> componentDecoder) throws IOException {
        final String raw = PlainTextComponentSerializer.plainText().serialize(input);
        final CompoundBinaryTag contents = EngineAdventure.NBT_CODEC.decode(raw);
        return HoverEvent.ShowEntity.showEntity(
                Key.key(contents.getString(ENTITY_TYPE, "")),
                UUID.fromString(Objects.requireNonNullElse(contents.getString(ENTITY_ID), "")),
                componentDecoder.decode(Objects.requireNonNullElse(contents.getString(ENTITY_NAME), ""))
        );
    }

    @Override
    public Component serializeShowItem(HoverEvent.ShowItem input) throws IOException {
        CompoundBinaryTag.Builder tag = CompoundBinaryTag.builder();
        tag.putString(ITEM_TYPE, input.item().asString());
        tag.putByte(ITEM_COUNT, (byte) input.count());
        final BinaryTagHolder nbt = input.nbt();
        if (nbt != null) tag.put(ITEM_TAG, nbt.get(EngineAdventure.NBT_CODEC));
        return Component.text(EngineAdventure.NBT_CODEC.encode(tag.build()));
    }

    @Override
    public Component serializeShowEntity(HoverEvent.ShowEntity input, Codec.Encoder<Component, String, ? extends RuntimeException> componentEncoder) throws IOException {
        CompoundBinaryTag.Builder tag = CompoundBinaryTag.builder();
        tag.putString(ENTITY_ID, input.id().toString());
        tag.putString(ENTITY_TYPE, input.type().asString());
        final Component name = input.name();
        if (name != null) tag.putString(ENTITY_NAME, componentEncoder.encode(name));
        return Component.text(EngineAdventure.NBT_CODEC.encode(tag.build()));
    }
}
