package io.github.bearisbug.synaxis.event.instance;

import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.event.trait.BlockEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;

/**
 * Called when a block in an instance is updated.
 * <p>
 * This event is triggered when a block's state changes from its instance.
 * If you wish to listen to all block updates, must be used in conjunction with {@link InstanceSectionInvalidateEvent}
 * <p>
 * Called on the thread that set the block, inside that call, holding the monitor of the instance and the write lock
 * of the chunk of the block. A listener must not take an entity another tick thread owns
 * ({@code acquirable().sync(...)} or {@code lock()}) nor wait for a future that is not done: that thread may be
 * waiting for this monitor to set a block, and the two threads deadlock. Hand an effect on another entity to the
 * thread of that entity with its scheduler instead.
 */
public class InstanceBlockUpdateEvent implements BlockEvent {
    private final Instance instance;
    private final BlockVec blockPosition;
    private final Block block;

    public InstanceBlockUpdateEvent(Instance instance, BlockVec blockPosition, Block block) {
        this.instance = instance;
        this.blockPosition = blockPosition;
        this.block = block;
    }

    @Override
    public Block getBlock() {
        return block;
    }

    @Override
    public BlockVec getBlockPosition() {
        return blockPosition;
    }

    @Override
    public Instance getInstance() {
        return instance;
    }
}
