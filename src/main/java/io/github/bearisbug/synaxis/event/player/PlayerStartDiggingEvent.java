package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.BlockEvent;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.coordinate.BlockFace;

/**
 * Called when a {@link Player} start digging a block,
 * can be used to forbid the {@link Player} from mining it.
 * <p>
 * Be aware that cancelling this event does not necessary prevent the player from breaking the block
 * (could be because of high latency or a modified client) so cancelling {@link PlayerBlockBreakEvent} is also necessary.
 * The engine does not check how long the player dug, an item still pending in {@code docs/DESIGN-PROD.md} §28.2.
 * A block broken instantly does not call this event.
 */
public class PlayerStartDiggingEvent implements PlayerInstanceEvent, BlockEvent, CancellableEvent {

    private final Player player;
    private final Instance instance;
    private final Block block;
    private final BlockVec blockPosition;
    private final BlockFace blockFace;

    private boolean cancelled;

    public PlayerStartDiggingEvent(Player player, Instance instance, Block block,
                                   BlockVec blockPosition,
                                   BlockFace blockFace) {
        this.player = player;
        this.instance = instance;
        this.block = block;
        this.blockPosition = blockPosition;
        this.blockFace = blockFace;
    }

    @Override
    public Instance getInstance() {
        return instance;
    }

    /**
     * Gets the block which is being dug.
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

    /**
     * Gets the face you are digging
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
