package io.github.bearisbug.synaxis.tag;

import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.item.component.CustomData;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TagTransientTest {

    @Test
    public void twoTransientTags() {
        var tagHandler = TagHandler.newHandler();
        Tag<String> tag1 = Tag.Transient("a");
        Tag<String> tag2 = Tag.Transient("b");

        tagHandler.setTag(tag1, "abcdef");
        var result = tagHandler.getTag(tag2);
        assertNull(result);
    }

    @Test
    public void twoTransientTagsEqual() {
        var tagHandler = TagHandler.newHandler();
        Tag<String> tag1 = Tag.Transient("a");
        Tag<String> tag2 = Tag.Transient("a");

        tagHandler.setTag(tag1, "abcdef");
        var result = tagHandler.getTag(tag2);
        assertEquals("abcdef", result);
    }

    @Test
    public void tagHandlerCopyPreservesTransient() {
        var tagHandler = TagHandler.newHandler();
        Tag<String> tag = Tag.Transient("a");
        tagHandler.setTag(tag, "abcdef");

        var copyHandler = tagHandler.copy();
        var result = copyHandler.getTag(tag);
        assertEquals("abcdef", result);
    }

    /**
     * A transient value has no nbt representation, so reading the same key through a different tag type has to
     * fall back to the default instead of dereferencing a null tag.
     */
    @Test
    public void readingTransientValueThroughAnotherTagType() {
        var tagHandler = TagHandler.newHandler();
        tagHandler.setTag(Tag.Transient("a"), "abcdef");

        assertNull(tagHandler.getTag(Tag.Integer("a")));
        assertNull(tagHandler.getTag(Tag.String("a")));
    }

    @Test
    public void asCompoundDoesNotPreserveTransient() {
        var tagHandler = TagHandler.newHandler();
        Tag<String> tag = Tag.Transient("a");
        tagHandler.setTag(tag, "abcdef");

        var compound = tagHandler.asCompound();
        assertNull(compound.get("a"));
    }

    /**
     * Custom data and blocks are NBT, which has nowhere to keep a transient value: writing one removes the key, as the
     * compound of a tag handler leaves it out. Kept as a null entry, it threw out of whatever wrote the NBT.
     */
    @Test
    public void aTransientValueIsNotKeptInNbt() {
        Tag<Boolean> tag = Tag.Transient("kit");
        var data = new CustomData(CompoundBinaryTag.builder().putBoolean("kit", true).putInt("level", 3).build());

        assertEquals(CompoundBinaryTag.builder().putInt("level", 3).build(), data.withTag(tag, true).nbt());
        assertNull(Block.STONE.withTag(tag, true).nbt());
    }

}
