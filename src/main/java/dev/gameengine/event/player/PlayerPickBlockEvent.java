package dev.gameengine.event.player;

import dev.gameengine.coordinate.BlockVec;
import dev.gameengine.entity.Player;
import dev.gameengine.event.trait.BlockEvent;
import dev.gameengine.event.trait.PlayerInstanceEvent;
import dev.gameengine.instance.Instance;
import dev.gameengine.instance.block.Block;

/**
 * Called when a player tries to pick a block (middle-click).
 */
public class PlayerPickBlockEvent implements PlayerInstanceEvent, BlockEvent {

    private final Player player;

    private final Instance instance;
    private final Block block;
    private final BlockVec blockPosition;
    private final boolean includeData;

    public PlayerPickBlockEvent(Player player, Instance instance, Block block,
                                BlockVec blockPosition, boolean includeData) {
        this.player = player;
        this.instance = instance;
        this.block = block;
        this.blockPosition = blockPosition;
        this.includeData = includeData;
    }

    @Override
    public Instance getInstance() {
        return instance;
    }

    /**
     * Gets the block which was picked.
     *
     * @return the block which was picked
     */
    @Override
    public Block getBlock() {
        return block;
    }

    /**
     * Gets the picked block position.
     *
     * @return the picked block position
     */
    @Override
    public BlockVec getBlockPosition() {
        return blockPosition;
    }

    /**
     * Get if the entity data should be included in the result (control middle-click).
     *
     * @return if the entity data should be included.
     */
    public boolean isIncludeData() {
        return this.includeData;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
