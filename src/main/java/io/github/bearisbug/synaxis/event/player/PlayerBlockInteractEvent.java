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
 * Called when a player interacts with a block (right-click), before anything else the click does,
 * a block placement included.
 * <p>
 * Cancelling it only skips {@link io.github.bearisbug.synaxis.instance.block.BlockHandler#onInteract}
 * of the clicked block: the item use or the placement behind it still happens. To stop those, set
 * {@link #setBlockingItemUse(boolean)} to true, or cancel {@link PlayerBlockPlaceEvent}.
 */
public class PlayerBlockInteractEvent implements PlayerInstanceEvent, BlockEvent, CancellableEvent {

    private final Player player;
    private final PlayerHand hand;
    private final Instance instance;
    private final Block block;
    private final BlockVec blockPosition;
    private final Point cursorPosition;
    private final BlockFace blockFace;

    /**
     * Does this interaction block the normal item use?
     * True for containers which open an inventory instead of letting blocks be placed
     */
    private boolean blocksItemUse;

    private boolean cancelled;

    public PlayerBlockInteractEvent(Player player, PlayerHand hand, Instance instance,
                                    Block block, BlockVec blockPosition, Point cursorPosition,
                                    BlockFace blockFace) {
        this.player = player;
        this.hand = hand;
        this.instance = instance;
        this.block = block;
        this.blockPosition = blockPosition;
        this.cursorPosition = cursorPosition;
        this.blockFace = blockFace;
    }

    /**
     * Gets if the event should block the item use.
     *
     * @return true if the item use is blocked, false otherwise
     */
    public boolean isBlockingItemUse() {
        return blocksItemUse;
    }

    /**
     * Sets the blocking item use state of this event
     * Note: If this is true, then no {@link PlayerUseItemOnBlockEvent} will be fired.
     * @param blocks - true to block item interactions, false to not block
     */
    public void setBlockingItemUse(boolean blocks) {
        this.blocksItemUse = blocks;
    }

    @Override
    public Instance getInstance() {
        return instance;
    }

    @Override
    public Block getBlock() {
        return block;
    }

    /**
     * Gets the position of the interacted block.
     *
     * @return the block position
     */
    @Override
    public BlockVec getBlockPosition() {
        return blockPosition;
    }

    /**
     * Gets the cursor position of the interacted block
     * @return the cursor position of the interaction
     */
    public Point getCursorPosition() { return cursorPosition; }

    /**
     * Gets the hand used for the interaction.
     *
     * @return the hand used
     */
    public PlayerHand getHand() {
        return hand;
    }

    /**
     * Gets the block face.
     *
     * @return the block face
     */
    public BlockFace getBlockFace() {
        return blockFace;
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
