package dev.gameengine.entity.metadata.item;

import dev.gameengine.entity.Entity;
import dev.gameengine.entity.MetadataDef;
import dev.gameengine.entity.MetadataHolder;
import dev.gameengine.entity.metadata.EntityMeta;
import dev.gameengine.entity.metadata.ObjectDataProvider;
import dev.gameengine.entity.metadata.projectile.ProjectileMeta;
import dev.gameengine.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public class SmallFireballMeta extends EntityMeta implements ObjectDataProvider, ProjectileMeta {
    private @Nullable Entity shooter;

    public SmallFireballMeta(@Nullable Entity entity, MetadataHolder metadata) {
        super(entity, metadata);
    }

    public ItemStack getItem() {
        return metadata.get(MetadataDef.SmartFireball.ITEM);
    }

    public void setItem(ItemStack item) {
        metadata.set(MetadataDef.SmartFireball.ITEM, item);
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
