package io.github.bearisbug.synaxis.tag;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;

import static io.github.bearisbug.synaxis.testing.TestUtils.assertEqualsSNBT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

public class TagHandlerReadableCopyTest {

    @Test
    public void copyCache() {
        var tag = Tag.String("key");
        var handler = TagHandler.newHandler();
        handler.setTag(tag, "test");

        var copy = handler.readableCopy();
        assertEquals(handler.getTag(tag), copy.getTag(tag));

        handler.setTag(tag, "test2");
        assertEquals("test2", handler.getTag(tag));
        assertEquals("test", copy.getTag(tag));
    }

    @Test
    public void copyCachePath() {
        var tag = Tag.String("key").path("path");
        var handler = TagHandler.newHandler();
        handler.setTag(tag, "test");
        assertEqualsSNBT("""
                {"path":{"key":"test"}}
                """, handler.asCompound());

        var copy = handler.readableCopy();
        handler.setTag(tag, "test2");
        assertEquals("test2", handler.getTag(tag));
        assertEquals("test", copy.getTag(tag));
    }

    @Test
    public void copyCacheReuse() {
        var handler = TagHandler.newHandler();
        handler.setTag(Tag.String("key"), "test");
        assertSame(handler.readableCopy(), handler.readableCopy());
    }

    @Test
    public void copyRehashing() {
        var tag = Tag.String("key");
        var handler = TagHandler.newHandler();
        handler.setTag(tag, "test");
        var copy = handler.readableCopy();
        for (int i = 0; i < 1000; i++) {
            handler.setTag(Tag.Integer("copyRehashing" + i), i);
        }
        assertEquals("test", handler.getTag(tag));
        assertEquals("test", copy.getTag(tag));

        handler.setTag(tag, "test2");
        assertEquals("test2", handler.getTag(tag));
        assertEquals("test", copy.getTag(tag));
    }

    /** Replacing the content changes every tag, so the copy taken before is stale like after any setTag. */
    @Test
    public void updateContentInvalidatesTheCopy() {
        var tag = Tag.Integer("a");
        var handler = TagHandler.newHandler();
        handler.setTag(tag, 1);
        assertEquals(1, handler.readableCopy().getTag(tag));

        handler.updateContent(CompoundBinaryTag.empty());
        assertNull(handler.readableCopy().getTag(tag));
        handler.updateContent(CompoundBinaryTag.builder().putInt("a", 2).build());
        assertEquals(2, handler.readableCopy().getTag(tag));
    }
}
