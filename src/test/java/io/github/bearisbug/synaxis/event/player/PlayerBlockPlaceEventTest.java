package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PlayerBlockPlaceEventTest {

    // The event only stores them, a real player or instance needs a running process
    private static final @Nullable Player NO_PLAYER = null;
    private static final @Nullable Instance NO_INSTANCE = null;

    private static PlayerBlockPlaceEvent event(Block block) {
        return new PlayerBlockPlaceEvent(NO_PLAYER, NO_INSTANCE, block, BlockFace.TOP,
                new BlockVec(1, 2, 3), new BlockVec(0, 0, 0), PlayerHand.MAIN);
    }

    @Test
    public void aPlacementConsumesTheBlockAndUpdatesNeighboursByDefault() {
        var event = event(Block.STONE);
        assertTrue(event.doesConsumeBlock());
        assertTrue(event.shouldDoBlockUpdates());
        assertFalse(event.isCancelled());
    }

    @Test
    public void thePlacedBlockCanBeSwappedByAListener() {
        var event = event(Block.STONE);
        assertSame(Block.STONE, event.getBlock());

        event.setBlock(Block.DIRT);
        assertSame(Block.DIRT, event.getBlock());
    }

    @Test
    public void theGeometryOfThePlacementIsKeptAsGiven() {
        var event = event(Block.STONE);
        assertSame(BlockFace.TOP, event.getBlockFace());
        assertEquals(new BlockVec(1, 2, 3), event.getBlockPosition());
        assertEquals(new BlockVec(0, 0, 0), event.getCursorPosition());
        assertSame(PlayerHand.MAIN, event.getHand());
    }

    @Test
    public void bothPlacementSideEffectsCanBeTurnedOff() {
        var event = event(Block.STONE);
        event.consumeBlock(false);
        event.setDoBlockUpdates(false);
        assertFalse(event.doesConsumeBlock());
        assertFalse(event.shouldDoBlockUpdates());
    }
}
