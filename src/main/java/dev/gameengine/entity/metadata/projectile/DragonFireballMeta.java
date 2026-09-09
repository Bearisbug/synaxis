package dev.gameengine.entity.metadata.projectile;

import dev.gameengine.entity.Entity;
import dev.gameengine.entity.MetadataHolder;
import dev.gameengine.entity.metadata.EntityMeta;
import dev.gameengine.entity.metadata.ObjectDataProvider;
import org.jetbrains.annotations.Nullable;

public class DragonFireballMeta extends EntityMeta implements ObjectDataProvider, ProjectileMeta {
    private @Nullable Entity shooter;

    public DragonFireballMeta(@Nullable Entity entity, MetadataHolder metadata) {
        super(entity, metadata);
    }

    @Override
    @Nullable
    public Entity getShooter() {
        return shooter;
    }

    @Override
    public void setShooter(@Nullable Entity shooter) {
        this.shooter = shooter;
    }

    @Override
    public int getObjectData() {
        return this.shooter == null ? 0 : this.shooter.getEntityId();
    }

    @Override
    public boolean requiresVelocityPacketAtSpawn() {
        return true;
    }

}
