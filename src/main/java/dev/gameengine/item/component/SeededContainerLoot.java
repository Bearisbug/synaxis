package dev.gameengine.item.component;

import dev.gameengine.codec.Codec;
import dev.gameengine.codec.StructCodec;

public record SeededContainerLoot(String lootTable, long seed) {
    public static final Codec<SeededContainerLoot> CODEC = StructCodec.struct(
            "loot_table", Codec.STRING, SeededContainerLoot::lootTable,
            "seed", Codec.LONG, SeededContainerLoot::seed,
            SeededContainerLoot::new);
}
