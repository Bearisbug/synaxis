package io.github.bearisbug.synaxis.instance.block.rule;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import org.jetbrains.annotations.Nullable;

/**
 * Chooses the state of a block when it is placed and when a block next to it changes. A rule is registered for a
 * block type ({@link Block#id()}) with
 * {@link io.github.bearisbug.synaxis.instance.block.BlockManager#registerBlockPlacementRule(BlockPlacementRule)}:
 * every state of the type shares it.
 */
public abstract class BlockPlacementRule {
    public static final int DEFAULT_UPDATE_RANGE = 10;

    /** The block given to the constructor, naming the block type of the rule: not the block placed or updated. */
    protected final Block block;

    protected BlockPlacementRule(Block block) {
        this.block = block;
    }

    /**
     * Called on this block after a block next to it was set, {@code fromFace} pointing to that block; a block just
     * placed is not called for its own placement. When the block returned is not the same object as
     * {@code currentBlock}, it is set in its turn and its own neighbours are updated, see
     * {@link #maxUpdateDistance()}.
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
     * Called only by {@link io.github.bearisbug.synaxis.instance.Instance#placeBlock} with block updates on, the way
     * a player places a block; {@link io.github.bearisbug.synaxis.instance.Instance#setBlock} does not call it. Start
     * from {@code placementState.block()}, the block placed, not from the field {@link #block}.
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

    /**
     * Returns the block given to the constructor, naming the block type of the rule: not the block placed or updated.
     *
     * @return the block of the rule
     */
    public Block getBlock() {
        return block;
    }

    /**
     * The depth of chained neighbour updates from which this rule is no longer called. The neighbours of a block set
     * from outside are at depth 0, those of a block an update changed one deeper; a block of this rule at a depth of
     * at least this value is not given {@link #blockUpdate(UpdateState)}. The depth counts the blocks of every rule
     * along the chain, not only this one.
     */
    public int maxUpdateDistance() {
        return DEFAULT_UPDATE_RANGE;
    }

    /**
     * What {@link #blockPlace(PlacementState)} is given. Without a player placing, {@code blockFace},
     * {@code cursorPosition}, {@code playerPosition} and {@code usedItemStack} are null.
     *
     * @param instance         the {@link io.github.bearisbug.synaxis.instance.Instance} of the block; reading a chunk
     *                         that is not loaded throws {@link NullPointerException}, so check a neighbour's chunk
     *                         first, for example with {@code ((Instance) instance).isChunkLoaded(point)}
     * @param block            the block placed
     * @param blockFace        the face of the block clicked
     * @param placePosition    the position of the block placed
     * @param cursorPosition   the position of the cursor on the face clicked
     * @param playerPosition   the position of the player
     * @param usedItemStack    the item used
     * @param isPlayerShifting whether the player is sneaking
     */
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

    /**
     * What {@link #blockUpdate(UpdateState)} is given.
     *
     * @param instance      the {@link io.github.bearisbug.synaxis.instance.Instance} of the block; reading a chunk that
     *                      is not loaded throws {@link NullPointerException}, so check a neighbour's chunk first, for
     *                      example with {@code ((Instance) instance).isChunkLoaded(point)}. An exception the rule
     *                      throws leaves the setBlock that led to the update with its block written and neither the
     *                      block change packet nor the {@code InstanceBlockUpdateEvent} sent
     * @param blockPosition the position of this block
     * @param currentBlock  this block
     * @param fromFace      the face of this block toward the block set
     */
    public record UpdateState(Block.Getter instance,
                              Point blockPosition,
                              Block currentBlock,
                              BlockFace fromFace) {
    }

    /**
     * What {@link #isSelfReplaceable(Replacement)} is given.
     *
     * @param block          the block in place
     * @param blockFace      the face clicked
     * @param cursorPosition the position of the cursor on the face clicked
     * @param isOffset       whether the placement position is offset from the position of the block clicked
     * @param material       the material of the item placed
     */
    public record Replacement(
            Block block,
            BlockFace blockFace,
            Point cursorPosition,
            boolean isOffset,
            Material material
    ) {
    }
}
