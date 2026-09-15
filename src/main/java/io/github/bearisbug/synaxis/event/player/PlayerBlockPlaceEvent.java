package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.event.trait.BlockEvent;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.coordinate.BlockFace;

/**
 * Called when a player tries placing a block.
 */
public class PlayerBlockPlaceEvent implements PlayerInstanceEvent, BlockEvent, CancellableEvent {

    private final Player player;
    private final Instance instance;
    private Block block;
    private final BlockFace blockFace;
    private final BlockVec blockPosition;
    private final Point cursorPosition;
    private final PlayerHand hand;

    private boolean consumeBlock;
    private boolean doBlockUpdates;

    private boolean cancelled;

    public PlayerBlockPlaceEvent(Player player, Instance instance, Block block,
                                 BlockFace blockFace, BlockVec blockPosition,
                                 Point cursorPosition, PlayerHand hand) {
        this.player = player;
        this.instance = instance;
        this.block = block;
        this.blockFace = blockFace;
        this.blockPosition = blockPosition;
        this.cursorPosition = cursorPosition;
        this.hand = hand;
        this.consumeBlock = true;
        this.doBlockUpdates = true;
    }

    @Override
    public Instance getInstance() {
        return instance;
    }

    /**
     * Gets the block which will be placed.
     *
     * @return the block to place
     */
    @Override
    public Block getBlock() {
        return block;
    }

    /**
     * Changes the block to be placed.
     *
     * @param block the new block
     */
    public void setBlock(Block block) {
        this.block = block;
    }

    public BlockFace getBlockFace() {
        return blockFace;
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

    public Point getCursorPosition() {
        return cursorPosition;
    }

    /**
     * Gets the hand with which the player is trying to place.
     *
     * @return the hand used
     */
    public PlayerHand getHand() {
        return hand;
    }

    /**
     * Should the block be consumed if not cancelled.
     *
     * @param consumeBlock true if the block should be consumer (-1 amount), false otherwise
     */
    public void consumeBlock(boolean consumeBlock) {
        this.consumeBlock = consumeBlock;
    }

    /**
     * Should the block be consumed if not cancelled.
     *
     * @return true if the block will be consumed, false otherwise
     */
    public boolean doesConsumeBlock() {
        return consumeBlock;
    }

    /**
     * Should the place trigger updates (on self and neighbors)
     * @param doBlockUpdates true if this placement should do block updates
     */
    public void setDoBlockUpdates(boolean doBlockUpdates) {
        this.doBlockUpdates = doBlockUpdates;
    }

    /**
     * Should the place trigger updates (on self and neighbors)
     * @return true if this placement should do block updates
     */
    public boolean shouldDoBlockUpdates() {
        return doBlockUpdates;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
