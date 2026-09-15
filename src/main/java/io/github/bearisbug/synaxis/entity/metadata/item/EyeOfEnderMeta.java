package io.github.bearisbug.synaxis.entity.metadata.item;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.MetadataDef;
import io.github.bearisbug.synaxis.entity.MetadataHolder;
import io.github.bearisbug.synaxis.entity.metadata.EntityMeta;
import io.github.bearisbug.synaxis.item.ItemStack;
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
