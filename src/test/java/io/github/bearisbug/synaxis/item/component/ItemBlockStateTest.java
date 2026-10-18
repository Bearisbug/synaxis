package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.instance.block.Block;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The client predicts a placement with the {@code block_state} of the item and skips what the block cannot take, so
 * the server has to place the same block instead of throwing on the tick thread.
 */
public class ItemBlockStateTest {

    @Test
    public void aValueThePropertyDoesNotTakeIsSkipped() {
        assertEquals(Block.OAK_LOG, new ItemBlockState("axis", "diagonal").apply(Block.OAK_LOG));
    }

    @Test
    public void theValidPropertiesAreAppliedNextToSkippedOnes() {
        var state = new ItemBlockState(Map.of("axis", "x", "facing", "east", "lit", "maybe"));
        assertEquals(Block.OAK_LOG.withProperty("axis", "x"), state.apply(Block.OAK_LOG));
        assertEquals(Block.FURNACE.withProperty("facing", "east"), state.apply(Block.FURNACE));
    }
}
