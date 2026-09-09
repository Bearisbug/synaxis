package dev.gameengine.entity.metadata.item;

import dev.gameengine.entity.Entity;
import dev.gameengine.entity.MetadataDef;
import dev.gameengine.entity.MetadataHolder;
import dev.gameengine.entity.metadata.EntityMeta;
import dev.gameengine.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public class EyeOfEnderMeta extends EntityMeta {
    public EyeOfEnderMeta(@Nullable Entity entity, MetadataHolder metadata) {
        super(entity, metadata);
    }

    public ItemStack getItem() {
        return metadata.get(MetadataDef.EyeOfEnder.ITEM);
    }

    public void setItem(ItemStack value) {
        metadata.set(MetadataDef.EyeOfEnder.ITEM, value);
    }

}
