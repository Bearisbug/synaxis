package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.BlockEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;

/**
 * Called when a {@link Player} stops digging a block before it is broken
 */
public class PlayerCancelDiggingEvent implements PlayerInstanceEvent, BlockEvent {
    private final Player player;
    private final Instance instance;
    private final Block block;
    private final BlockVec blockPosition;

    public PlayerCancelDiggingEvent(Player player, Instance instance, Block block, BlockVec blockPosition) {
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
     * Gets the block which was being dug.
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
