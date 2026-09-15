package io.github.bearisbug.synaxis.event.trait;

import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;

/**
 * Represents an event related to a {@link Block} happening in an {@link Instance}.
 */
public interface BlockEvent extends InstanceEvent {
    Block getBlock();

    BlockVec getBlockPosition();
}
