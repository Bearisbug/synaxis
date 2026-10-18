package io.github.bearisbug.synaxis.tag;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import io.github.bearisbug.synaxis.item.component.CustomData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TagUuidTest {

    @Test
    public void get() {
        var uuid = UUID.randomUUID();
        var tag = Tag.UUID("uuid");
        var handler = TagHandler.newHandler();
        handler.setTag(tag, uuid);
        assertEquals(uuid, handler.getTag(tag));
    }

    @Test
    public void empty() {
        var tag = Tag.UUID("uuid");
        var handler = TagHandler.newHandler();
        assertNull(handler.getTag(tag));
    }

    @Test
    public void invalidTag() {
        var tag = Tag.UUID("entry");
        var handler = TagHandler.newHandler();
        handler.setTag(Tag.Integer("entry"), 1);
        assertNull(handler.getTag(tag));
    }

    @Test
    public void toNbt() {
        var tag = Tag.UUID("uuid");
        var handler = TagHandler.newHandler();
        handler.setTag(tag, UUID.fromString("9ab8ca63-3d7b-43ba-b805-a20a352dae9c"));
        var nbt = handler.asCompound();
        IntArrayBinaryTag array = (IntArrayBinaryTag) nbt.get("uuid");
        assertArrayEquals(new int[]{-1699165597, 1031488442, -1207590390, 892186268}, array.value());
    }

    @Test
    public void fromNbt() {
        var tag = Tag.UUID("uuid");
        var handler = TagHandler.newHandler();
        handler.setTag(Tag.NBT("uuid"), IntArrayBinaryTag.intArrayBinaryTag(-1699165597, 1031488442, -1207590390, 892186268));
        assertEquals(UUID.fromString("9ab8ca63-3d7b-43ba-b805-a20a352dae9c"), handler.getTag(tag));
    }

    /**
     * Custom data can come from a client, so an int array of another length is no UUID, whether it is read from a
     * handler or from the custom data of an item, instead of an exception out of the reader.
     */
    @Test
    public void anArrayOfAnotherLengthIsNoUuid() {
        var tag = Tag.UUID("entry");
        var handler = TagHandler.newHandler();
        handler.setTag(Tag.NBT("entry"), IntArrayBinaryTag.intArrayBinaryTag(1));
        assertNull(handler.getTag(tag));

        var data = new CustomData(CompoundBinaryTag.builder().putIntArray("entry", new int[]{1, 2}).build());
        assertNull(data.getTag(tag));
    }
}
