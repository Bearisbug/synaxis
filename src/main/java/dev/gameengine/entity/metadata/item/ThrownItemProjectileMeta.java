package dev.gameengine.entity.metadata.item;

import dev.gameengine.entity.Entity;
import dev.gameengine.entity.MetadataDef;
import dev.gameengine.entity.MetadataHolder;
import dev.gameengine.entity.metadata.EntityMeta;
import dev.gameengine.item.ItemStack;
import org.jetbrains.annotations.Nullable;

class ThrownItemProjectileMeta extends EntityMeta {
    protected ThrownItemProjectileMeta(@Nullable Entity entity, MetadataHolder metadata) {
        super(entity, metadata);
    }

    public ItemStack getItem() {
        return metadata.get(MetadataDef.ThrownItemProjectile.ITEM);
    }

    public void setItem(ItemStack item) {
        metadata.set(MetadataDef.ThrownItemProjectile.ITEM, item);
    }

}
