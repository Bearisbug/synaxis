package dev.gameengine.event.entity.projectile;

import dev.gameengine.coordinate.Pos;
import dev.gameengine.entity.Entity;
import dev.gameengine.instance.block.Block;

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
