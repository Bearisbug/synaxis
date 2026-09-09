package dev.gameengine.instance.block;

import net.kyori.adventure.key.Key;
import dev.gameengine.item.ItemStack;
import dev.gameengine.registry.Registries;
import dev.gameengine.tag.Tag;

import java.util.Collection;
import java.util.List;

public class SuspiciousGravelBlockHandler implements BlockHandler {
    public static final Tag<String> LOOT_TABLE = Tag.String("LootTable");

    private final Tag<ItemStack> item;
    private final boolean hasTags;

    public SuspiciousGravelBlockHandler(Registries registries, boolean hasTags) {
        this.item = Tag.ItemStack("item", registries);
        this.hasTags = hasTags;
    }

    @Override
    public Key getKey() {
        return Key.key("suspicious_gravel");
    }

    @Override
    public Collection<Tag<?>> getBlockEntityTags() {
        return hasTags ? List.of(LOOT_TABLE, item) : List.of();
    }
}
