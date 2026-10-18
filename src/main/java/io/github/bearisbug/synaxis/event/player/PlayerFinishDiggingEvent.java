package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.BlockEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;

/**
 * Called when the client of a {@link Player} reports it finished digging a block. The engine does not
 * check how long the player dug, a client can report the end without having reported a start
 * ({@code docs/DESIGN-PROD.md} §28.2, digging time not checked), and a modified client can report it
 * for a block that breaks instantly, which a vanilla client breaks with the start alone. The engine
 * then calls {@code Instance#breakBlock}, whose {@link PlayerBlockBreakEvent} can still be cancelled.
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
     * Changes the block a refused break is judged by: when {@code Instance#breakBlock} refuses the
     * break, its {@link PlayerBlockBreakEvent} cancelled or the chunk read-only, a player standing right
     * on top of the position is teleported back where it stands if this block is solid. It changes
     * neither the block that is broken nor the block {@link PlayerBlockBreakEvent} sees.
     *
     * @param block the block to judge a refused break by
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
