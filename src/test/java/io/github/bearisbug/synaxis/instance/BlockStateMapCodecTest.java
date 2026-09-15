package io.github.bearisbug.synaxis.instance;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.adventure.EngineAdventure;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.instance.block.Block;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static io.github.bearisbug.synaxis.instance.block.Block.STATE_STRUCT_CODEC;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class BlockStateMapCodecTest {
    @Test
    public void testEquivalence() throws IOException {
        String particleString = "{Properties:{lit:\"true\"},Name:\"copper_bulb\"}";
        CompoundBinaryTag nbt = EngineAdventure.NBT_CODEC.decode(particleString);
        Block block = STATE_STRUCT_CODEC.decode(Transcoder.NBT,nbt).orElseThrow();
        assertEquals("true", block.getProperty("lit"));
        assertEquals(block.defaultState().getProperty("powered"), block.getProperty("powered"));
        assertEquals("minecraft:copper_bulb", block.name());
        BinaryTag newNBT = STATE_STRUCT_CODEC.encode(Transcoder.NBT,block).orElseThrow();
        String newString = EngineAdventure.NBT_CODEC.encode((CompoundBinaryTag) newNBT);
        assertEquals(particleString, newString);
    }

    @Test
    public void testDefaultBlockState() throws IOException {
        String defaultFacing = Block.SPRUCE_STAIRS.defaultState().getProperty("facing");
        assert defaultFacing != null;
        Block block = Block.SPRUCE_STAIRS.withProperty("facing", defaultFacing);
        BinaryTag nbt = STATE_STRUCT_CODEC.encode(Transcoder.NBT, block).orElseThrow();
        String nbtString = EngineAdventure.NBT_CODEC.encode((CompoundBinaryTag) nbt);
        assertEquals("{Name:\"spruce_stairs\"}", nbtString);
    }

    @Test
    public void testPropertyBlockState() throws IOException {
        Block block = Block.SPRUCE_STAIRS.withProperty("facing", "south");
        BinaryTag nbt = STATE_STRUCT_CODEC.encode(Transcoder.NBT, block).orElseThrow();
        String nbtString = EngineAdventure.NBT_CODEC.encode((CompoundBinaryTag) nbt);
        assertEquals("{Properties:{facing:\"south\"},Name:\"spruce_stairs\"}", nbtString);
    }
}
