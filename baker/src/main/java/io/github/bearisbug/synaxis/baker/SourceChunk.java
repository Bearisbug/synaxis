package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.synx.SynxBlockEntity;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * One chunk as a {@link BakeSource} hands it to the baker. The arrays hold one entry per section of the
 * world, index {@code sectionY - minY / 16}.
 *
 * @param blocks         block state palettes
 * @param biomes         biome palettes, ids of the registry the bake runs with
 * @param blockEntities  every block entity with its complete NBT and handler namespace
 * @param entities       static entities, each a complete compound with {@code id}, {@code Pos} of three finite
 *                       doubles and {@code Rotation} of two floats (the Anvil source refuses any other, see
 *                       {@code AnvilWorld#readEntities})
 * @param lightOn        whether the source says its own light is complete ({@code isLightOn})
 * @param sourceSky      the source's sky light per section, null entries where it has none; null for a source without light
 * @param sourceBlock    the source's block light, as {@code sourceSky}
 * @param markerCells    cells the source light parity leaves out, {@link io.github.bearisbug.synaxis.baker.anvil.AnvilChunk#cellKey}: the data markers
 *                       the bake turned into air and the blocks below them
 */
public record SourceChunk(int chunkX, int chunkZ, Palette[] blocks, Palette[] biomes,
                          List<SynxBlockEntity> blockEntities, List<CompoundBinaryTag> entities, boolean lightOn,
                          byte @Nullable [] @Nullable [] sourceSky, byte @Nullable [] @Nullable [] sourceBlock,
                          Set<Long> markerCells) {
    public SourceChunk {
        if (blocks.length != biomes.length) throw new IllegalArgumentException("one biome palette per section");
        blockEntities = List.copyOf(blockEntities);
        entities = List.copyOf(entities);
        markerCells = Set.copyOf(markerCells);
    }
}
