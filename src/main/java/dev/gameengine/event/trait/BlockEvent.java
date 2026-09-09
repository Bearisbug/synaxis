package dev.gameengine.event.trait;

import dev.gameengine.coordinate.BlockVec;
import dev.gameengine.instance.Instance;
import dev.gameengine.instance.block.Block;

/**
 * Represents an event related to a {@link Block} happening in an {@link Instance}.
 */
public interface BlockEvent extends InstanceEvent {
    Block getBlock();

    BlockVec getBlockPosition();
}
