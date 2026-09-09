package dev.gameengine.entity.metadata.display;

import dev.gameengine.entity.Entity;
import dev.gameengine.entity.MetadataDef;
import dev.gameengine.entity.MetadataHolder;
import dev.gameengine.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public class ItemDisplayMeta extends AbstractDisplayMeta {
    public ItemDisplayMeta(@Nullable Entity entity, MetadataHolder metadata) {
        super(entity, metadata);
    }

    public ItemStack getItemStack() {
        return metadata.get(MetadataDef.ItemDisplay.DISPLAYED_ITEM);
    }

    public void setItemStack(ItemStack value) {
        metadata.set(MetadataDef.ItemDisplay.DISPLAYED_ITEM, value);
    }

    public DisplayContext getDisplayContext() {
        return DisplayContext.VALUES[metadata.get(MetadataDef.ItemDisplay.DISPLAY_TYPE)];
    }

    public void setDisplayContext(DisplayContext value) {
        metadata.set(MetadataDef.ItemDisplay.DISPLAY_TYPE, (byte) value.ordinal());
    }

    public enum DisplayContext {
        NONE,
        THIRDPERSON_LEFT_HAND,
        THIRDPERSON_RIGHT_HAND,
        FIRSTPERSON_LEFT_HAND,
        FIRSTPERSON_RIGHT_HAND,
        HEAD,
        GUI,
        GROUND,
        FIXED,
        ON_SHELF;

        private final static DisplayContext[] VALUES = values();

    }
}
