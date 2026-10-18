package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.BlockEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;

import java.util.List;

/**
 * Called when a player sends the text of a sign, for a block within its reach in a loaded chunk
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-030}). The lines are what the client sent: the engine
 * does not check that the block is a sign, nor that the sign editor was opened for this player, and
 * it writes nothing into the block. A listener that writes the text checks both first.
 */
public class PlayerEditSignEvent implements PlayerInstanceEvent, BlockEvent {
    private final Player player;
    private final Instance instance;
    private final Block block;
    private final BlockVec blockPosition;
    private final List<String> lines;
    private final boolean isFrontText;

    public PlayerEditSignEvent(Player player, Instance instance, Block block, BlockVec blockPosition, List<String> lines, boolean isFrontText) {
        this.player = player;
        this.instance = instance;
        this.block = block;
        this.blockPosition = blockPosition;
        this.lines = lines;
        this.isFrontText = isFrontText;
    }

    @Override
    public Instance getInstance() {
        return instance;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public Block getBlock() {
        return block;
    }

    @Override
    public BlockVec getBlockPosition() {
        return blockPosition;
    }

    /**
     * Returns a list of strings representing the lines typed by the player onto the sign.
     * The length is always exactly 4, each line at most 384 characters, and the list is unmodifiable.
     */
    public List<String> getLines() {
        return lines;
    }

    public boolean isFrontText() {
        return isFrontText;
    }
}
