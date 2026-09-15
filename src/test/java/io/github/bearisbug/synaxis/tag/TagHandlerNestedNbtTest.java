package io.github.bearisbug.synaxis.tag;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;

import static io.github.bearisbug.synaxis.testing.TestUtils.assertEqualsSNBT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Nodes converted from NBT must belong to the handler they end up in, otherwise a write through a
 * path invalidates the discarded conversion handler instead of the real root.
 */
public class TagHandlerNestedNbtTest {

    private static final Tag<Integer> NESTED = Tag.Integer("n").path("a");

    @Test
    public void writeInsideUpdatedContent() {
        var handler = TagHandler.newHandler();
        handler.updateContent(CompoundBinaryTag.builder()
                .put("a", CompoundBinaryTag.builder().putInt("n", 1).build())
                .build());
        assertEqualsSNBT("""
                {"a":{"n":1}}
                """, handler.asCompound());

        handler.setTag(NESTED, 2);
        assertEquals(2, handler.getTag(NESTED));
        assertEqualsSNBT("""
                {"a":{"n":2}}
                """, handler.asCompound());
    }

    @Test
    public void writeInsideNestedNbtValue() {
        var tag = Tag.Integer("n").path("a", "b");
        var handler = TagHandler.newHandler();
        handler.setTag(Tag.NBT("a"), CompoundBinaryTag.builder()
                .put("b", CompoundBinaryTag.builder().putInt("n", 1).build())
                .build());
        assertEqualsSNBT("""
                {"a":{"b":{"n":1}}}
                """, handler.asCompound());

        handler.setTag(tag, 2);
        assertEquals(2, handler.getTag(tag));
        assertEqualsSNBT("""
                {"a":{"b":{"n":2}}}
                """, handler.asCompound());
    }
}
