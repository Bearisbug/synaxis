package io.github.bearisbug.synaxis.instance;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.block.BlockUtils;
import io.github.bearisbug.synaxis.tag.Tag;
import io.github.bearisbug.synaxis.tag.TagSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.bearisbug.synaxis.testing.TestUtils.assertEqualsSNBT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Block tags follow the path of the tag, the same way the custom data of an item does.
 */
public class BlockTagPathTest {

    @Test
    void aTagWithAPathIsWrittenUnderThatPath() {
        final Tag<Integer> tag = Tag.Integer("n").path("p");
        final Block block = Block.STONE.withTag(tag, 5);

        assertEqualsSNBT("{p:{n:5}}", block.nbtOrEmpty());
        assertEquals(5, block.getTag(tag));
        assertNull(block.getTag(Tag.Integer("n")));
    }

    @Test
    void theSameKeyUnderTwoPathsKeepsBothValues() {
        final Block block = Block.STONE
                .withTag(Tag.Integer("n").path("a"), 1)
                .withTag(Tag.Integer("n").path("b"), 2);

        assertEqualsSNBT("{a:{n:1},b:{n:2}}", block.nbtOrEmpty());
        assertEquals(1, block.getTag(Tag.Integer("n").path("a")));
        assertEquals(2, block.getTag(Tag.Integer("n").path("b")));
    }

    @Test
    void removingTheLastTagUnderAPathLeavesNoNbt() {
        final Tag<Integer> tag = Tag.Integer("n").path("p");

        assertNull(Block.STONE.withTag(tag, 5).withTag(tag, null).nbt());
    }

    @Test
    void aViewTagReplacesTheWholeNbt() {
        final Block block = Block.STONE.withTag(Tag.Integer("old"), 1)
                .withTag(Tag.View(TagSerializer.COMPOUND), CompoundBinaryTag.builder().putInt("new", 2).build());

        assertEqualsSNBT("{new:2}", block.nbtOrEmpty());
    }

    @Test
    void theClientGetsTheTagsOfTheHandlerUnderTheirPath() {
        final Tag<Boolean> glowing = Tag.Boolean("has_glowing_text").path("front_text");
        final BlockHandler handler = new BlockHandler() {
            @Override
            public List<Tag<?>> getBlockEntityTags() {
                return List.of(glowing, Tag.String("absent"));
            }

            @Override
            public Key getKey() {
                return Key.key("synaxis:sign_test");
            }
        };
        final Block sign = Block.OAK_SIGN.withHandler(handler)
                .withTag(glowing, true)
                .withTag(Tag.String("owner").path("front_text"), "server only");

        assertEqualsSNBT("{front_text:{has_glowing_text:1b}}", BlockUtils.extractClientNbt(sign));
    }
}
