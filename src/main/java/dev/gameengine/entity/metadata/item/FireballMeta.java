package dev.gameengine.entity.metadata.item;

import dev.gameengine.entity.Entity;
import dev.gameengine.entity.MetadataDef;
import dev.gameengine.entity.MetadataHolder;
import dev.gameengine.entity.metadata.EntityMeta;
import dev.gameengine.entity.metadata.ObjectDataProvider;
import dev.gameengine.entity.metadata.projectile.ProjectileMeta;
import dev.gameengine.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public class FireballMeta extends EntityMeta implements ObjectDataProvider, ProjectileMeta {
    private Entity shooter;

    public FireballMeta(@Nullable Entity entity, MetadataHolder metadata) {
        super(entity, metadata);
    }

    public ItemStack getItem() {
        return metadata.get(MetadataDef.Fireball.ITEM);
    }

    public void setItem(ItemStack value) {
        metadata.set(MetadataDef.Fireball.ITEM, value);
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
