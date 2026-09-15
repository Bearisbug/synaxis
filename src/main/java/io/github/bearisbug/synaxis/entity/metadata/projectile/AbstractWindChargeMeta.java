package io.github.bearisbug.synaxis.entity.metadata.projectile;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.MetadataHolder;
import io.github.bearisbug.synaxis.entity.metadata.EntityMeta;
import io.github.bearisbug.synaxis.entity.metadata.ObjectDataProvider;
import org.jetbrains.annotations.Nullable;

public class AbstractWindChargeMeta extends EntityMeta implements ObjectDataProvider, ProjectileMeta {
    private @Nullable Entity shooter;

    public AbstractWindChargeMeta(@Nullable Entity entity, MetadataHolder metadata) {
        super(entity, metadata);
    }

    @Override
    public @Nullable Entity getShooter() {
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
