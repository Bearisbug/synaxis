package io.github.bearisbug.synaxis.instance.block.rule;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import org.jetbrains.annotations.Nullable;

public abstract class BlockPlacementRule {
    public static final int DEFAULT_UPDATE_RANGE = 10;

    protected final Block block;

    protected BlockPlacementRule(Block block) {
        this.block = block;
    }

    /**
     * Called when the block state id can be updated (for instance if a neighbour block changed).
     * This is first called on a newly placed block, and then this is called for all neighbors of the block
     * <p>
     * Runs inside the call that set the changed block, holding the monitor of the instance and the write lock of
     * the chunk of that block: do not take an entity another tick thread owns nor wait for a future here, see
     * {@link io.github.bearisbug.synaxis.instance.block.BlockHandler}.
     *
     * @param updateState The current parameters to the block update
     * @return the updated block
     */
    public Block blockUpdate(UpdateState updateState) {
        return updateState.currentBlock();
    }

    /**
     * Called when the block is placed.
     * It is recommended that you only set up basic properties on the block for this placement, such as determining facing, etc
     * <p>
     * Runs holding the monitor of the instance and the write lock of the chunk, with the same restrictions as
     * {@link #blockUpdate(UpdateState)}.
     *
     * @param placementState The current parameters to the block placement
     * @return the block to place, which its handler gets in
     * {@link io.github.bearisbug.synaxis.instance.block.BlockHandler#onPlace}, or {@code null} to cancel the
     * placement: the world is left as it is, and a player keeps the item and gets the block it predicted rolled back
     */
    public abstract @Nullable Block blockPlace(PlacementState placementState);

    public boolean isSelfReplaceable(Replacement replacement) {
        return false;
    }

    public Block getBlock() {
        return block;
    }

    /**
     * The max distance where a block update can be triggered. It is not based on block, so if the value is 3 and a completely
     * different block updates 3 blocks away it could still trigger an update.
     */
    public int maxUpdateDistance() {
        return DEFAULT_UPDATE_RANGE;
    }

    public record PlacementState(
            Block.Getter instance,
            Block block,
            @Nullable BlockFace blockFace,
            Point placePosition,
            @Nullable Point cursorPosition,
            @Nullable Pos playerPosition,
            @Nullable ItemStack usedItemStack,
            boolean isPlayerShifting
    ) {
    }

    public record UpdateState(Block.Getter instance,
                              Point blockPosition,
                              Block currentBlock,
                              BlockFace fromFace) {
    }

    public record Replacement(
            Block block,
            BlockFace blockFace,
            Point cursorPosition,
            /*
			  Whether the placement position is offset from the clicked block
			  position.
			 */
            boolean isOffset,
            Material material
    ) {
    }
}
