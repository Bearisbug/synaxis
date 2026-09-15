package io.github.bearisbug.synaxis.event.entity.projectile;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.instance.block.Block;

public final class ProjectileCollideWithBlockEvent extends ProjectileCollideEvent {

    private final Block block;

    public ProjectileCollideWithBlockEvent(
            Entity projectile,
            Pos position,
            Block block
    ) {
        super(projectile, position);
        this.block = block;
    }

    public Block getBlock() {
        return block;
    }
}
