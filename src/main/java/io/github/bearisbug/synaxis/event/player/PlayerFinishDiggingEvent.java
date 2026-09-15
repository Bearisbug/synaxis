package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.BlockEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;

/**
 * Called when a {@link Player} successfully finishes digging a block
 */
public class PlayerFinishDiggingEvent implements PlayerInstanceEvent, BlockEvent {
    private final Player player;
    private final Instance instance;
    private Block block;
    private final BlockVec blockPosition;

    public PlayerFinishDiggingEvent(Player player, Instance instance, Block block, BlockVec blockPosition) {
        this.player = player;
        this.instance = instance;
        this.block = block;
        this.blockPosition = blockPosition;
    }

    @Override
    public Instance getInstance() {
        return instance;
    }

    /**
     * Changes which block was dug
     * <p>
     * This has somewhat odd behavior;
     * If you set it from a previously solid block to a non-solid block
     * then cancel the respective {@link PlayerBlockBreakEvent}
     * it will allow the player to phase through the block and into the floor
     * (only if the player is standing on top of the block)
     *
     * @param block the block to set the result to
     */
    public void setBlock(Block block) {
        this.block = block;
    }

    /**
     * Gets the block which was dug.
     *
     * @return the block
     */
    @Override
    public Block getBlock() {
        return block;
    }

    /**
     * Gets the block position.
     *
     * @return the block position
     */
    @Override
    public BlockVec getBlockPosition() {
        return blockPosition;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
