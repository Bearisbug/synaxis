package dev.gameengine.entity.metadata.item;

import dev.gameengine.entity.Entity;
import dev.gameengine.entity.MetadataDef;
import dev.gameengine.entity.MetadataHolder;
import dev.gameengine.entity.metadata.EntityMeta;
import dev.gameengine.entity.metadata.ObjectDataProvider;
import dev.gameengine.item.ItemStack;
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
