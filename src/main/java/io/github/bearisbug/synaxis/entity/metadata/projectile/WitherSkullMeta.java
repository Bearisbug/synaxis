package io.github.bearisbug.synaxis.entity.metadata.projectile;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.MetadataDef;
import io.github.bearisbug.synaxis.entity.MetadataHolder;
import io.github.bearisbug.synaxis.entity.metadata.EntityMeta;
import io.github.bearisbug.synaxis.entity.metadata.ObjectDataProvider;
import org.jetbrains.annotations.Nullable;

public class WitherSkullMeta extends EntityMeta implements ObjectDataProvider, ProjectileMeta {
    private @Nullable Entity shooter;

    public WitherSkullMeta(@Nullable Entity entity, MetadataHolder metadata) {
        super(entity, metadata);
    }

    public boolean isInvulnerable() {
        return metadata.get(MetadataDef.WitherSkull.IS_INVULNERABLE);
    }

    public void setInvulnerable(boolean value) {
        metadata.set(MetadataDef.WitherSkull.IS_INVULNERABLE, value);
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
