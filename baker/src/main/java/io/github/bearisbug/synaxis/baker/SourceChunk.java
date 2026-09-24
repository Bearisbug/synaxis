package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.synx.SynxBlockEntity;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One chunk as a {@link BakeSource} hands it to the baker. The arrays hold one entry per section of the
 * world, index {@code sectionY - minY / 16}.
 *
 * @param blocks         block state palettes
 * @param biomes         biome palettes, ids of the registry the bake runs with
 * @param blockEntities  every block entity with its complete NBT and handler namespace
 * @param entities       static entities, each a complete compound with {@code id} and {@code Pos}
 * @param lightOn        whether the source says its own light is complete ({@code isLightOn})
 * @param sourceSky      the source's sky light per section, null entries where it has none; null for a source without light
 * @param sourceBlock    the source's block light, as {@code sourceSky}
 */
public record SourceChunk(int chunkX, int chunkZ, Palette[] blocks, Palette[] biomes,
                          List<SynxBlockEntity> blockEntities, List<CompoundBinaryTag> entities, boolean lightOn,
                          byte @Nullable [] @Nullable [] sourceSky, byte @Nullable [] @Nullable [] sourceBlock) {
    public SourceChunk {
        if (blocks.length != biomes.length) throw new IllegalArgumentException("one biome palette per section");
        blockEntities = List.copyOf(blockEntities);
        entities = List.copyOf(entities);
    }
}
