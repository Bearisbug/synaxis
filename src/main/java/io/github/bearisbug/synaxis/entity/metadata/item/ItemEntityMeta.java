package io.github.bearisbug.synaxis.entity.metadata.item;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.MetadataDef;
import io.github.bearisbug.synaxis.entity.MetadataHolder;
import io.github.bearisbug.synaxis.entity.metadata.EntityMeta;
import io.github.bearisbug.synaxis.entity.metadata.ObjectDataProvider;
import io.github.bearisbug.synaxis.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public class ItemEntityMeta extends EntityMeta implements ObjectDataProvider {
    public ItemEntityMeta(@Nullable Entity entity, MetadataHolder metadata) {
        super(entity, metadata);
    }

    public ItemStack getItem() {
        return metadata.get(MetadataDef.ItemEntity.ITEM);
    }

    public void setItem(ItemStack value) {
        metadata.set(MetadataDef.ItemEntity.ITEM, value);
    }

    @Override
    public int getObjectData() {
        return 1;
    }

    @Override
    public boolean requiresVelocityPacketAtSpawn() {
        return true;
    }

}
