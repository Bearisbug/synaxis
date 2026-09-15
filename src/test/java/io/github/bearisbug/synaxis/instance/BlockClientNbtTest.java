package io.github.bearisbug.synaxis.instance;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.tag.Tag;
import io.github.bearisbug.synaxis.instance.block.BlockUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class BlockClientNbtTest {

    @Test
    public void basic() {
        assertNull(BlockUtils.extractClientNbt(Block.STONE));
        assertNull(BlockUtils.extractClientNbt(Block.GRASS_BLOCK));
        assertEquals(CompoundBinaryTag.empty(), BlockUtils.extractClientNbt(Block.CHEST));

        var nbt = CompoundBinaryTag.builder().putString("test", "test").build();
        assertEquals(nbt, BlockUtils.extractClientNbt(Block.CHEST.withNbt(nbt)));
    }

    @Test
    public void handler() {
        var handler = new BlockHandler() {
            @Override
            public List<Tag<?>> getBlockEntityTags() {
                return List.of(Tag.String("test"));
            }

            @Override
            public Key getKey() {
                return Key.key("synaxis:test");
            }
        };

        var nbt = CompoundBinaryTag.builder().putString("test", "test").build();
        assertNull(BlockUtils.extractClientNbt(Block.STONE.withNbt(nbt).withHandler(handler)));
        assertEquals(nbt, BlockUtils.extractClientNbt(Block.CHEST.withNbt(nbt).withHandler(handler)));
        assertEquals(nbt, BlockUtils.extractClientNbt(Block.CHEST
                .withNbt(CompoundBinaryTag.builder().putString("test", "test").putString("test2", "test2").build())
                .withHandler(handler)));
    }
}
