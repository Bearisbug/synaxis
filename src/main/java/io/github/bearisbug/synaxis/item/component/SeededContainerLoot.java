package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;

public record SeededContainerLoot(String lootTable, long seed) {
    public static final Codec<SeededContainerLoot> CODEC = StructCodec.struct(
            "loot_table", Codec.STRING, SeededContainerLoot::lootTable,
            "seed", Codec.LONG, SeededContainerLoot::seed,
            SeededContainerLoot::new);
}
