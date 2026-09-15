package io.github.bearisbug.synaxis.entity.metadata.item;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.MetadataDef;
import io.github.bearisbug.synaxis.entity.MetadataHolder;
import io.github.bearisbug.synaxis.entity.metadata.EntityMeta;
import io.github.bearisbug.synaxis.item.ItemStack;
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
