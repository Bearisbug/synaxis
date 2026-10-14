package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.generator.GeneratorImpl;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.synx.SynxBlockEntity;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * An engine {@link io.github.bearisbug.synaxis.instance.generator.Generator} as a bake source: each chunk is
 * generated on its own, the way an instance generates it, into fresh sections. A generator that calls fork is
 * refused, even for blocks inside its own chunk: the baker does not apply forks, it reads chunks independently
 * and in any order, so a fork would make a chunk depend on which neighbour happened to be generated first.
 */
public final class GeneratorSource implements BakeSource {
    private final GeneratorPreset preset;
    private final Registries registries;

    /**
     * Takes a preset whose world the baker can write.
     *
     * @throws IllegalArgumentException if min_y is not a multiple of 16 or the height is not one from 16 to 256
     */
    public GeneratorSource(GeneratorPreset preset, Registries registries) {
        if (preset.minY() % 16 != 0 || preset.height() <= 0 || preset.height() % 16 != 0 || preset.height() > 256) {
            throw new IllegalArgumentException("the generator preset has min_y " + preset.minY() + " and height "
                    + preset.height() + "; both must be multiples of 16 and the height 16 to 256 (a far view y index"
                    + " is one byte)");
        }
        this.preset = preset;
        this.registries = registries;
    }

    @Override
    public String kind() {
        return "generator";
    }

    @Override
    public int minY() {
        return preset.minY();
    }

    @Override
    public int height() {
        return preset.height();
    }

    @Override
    public SynxManifest.Bounds bounds() {
        return preset.bounds();
    }

    @Override
    public @Nullable Integer dataVersion() {
        return null;
    }

    @Override
    public @Nullable Double lightCoverage() {
        return null;
    }

    @Override
    public void checkGate(int threads) {
    }

    /**
     * Generates one chunk into fresh sections.
     *
     * @throws IllegalStateException if the generator throws, or calls fork on the chunk or on one of its sections
     */
    @Override
    public SourceChunk read(int chunkX, int chunkZ) {
        final int sections = preset.height() / 16, minSection = preset.minY() / 16;
        final GeneratorImpl.GenSection[] generated = new GeneratorImpl.GenSection[sections];
        for (int i = 0; i < sections; i++) generated[i] = new GeneratorImpl.GenSection();
        final GeneratorImpl.UnitImpl unit = GeneratorImpl.chunk(registries.biome(), generated, chunkX, minSection, chunkZ);
        try {
            preset.generator().generate(unit);
        } catch (RuntimeException e) {
            throw new IllegalStateException("chunk " + chunkX + ", " + chunkZ + ": the generator failed: " + e, e);
        }
        // A section of subdivide() keeps the forks made on it, the chunk unit does not see them
        final boolean forked = !unit.forks().isEmpty() || unit.subdivide().stream()
                .anyMatch(section -> !((GeneratorImpl.UnitImpl) section).forks().isEmpty());
        if (forked) {
            throw new IllegalStateException("chunk " + chunkX + ", " + chunkZ + ": the generator forked"
                    + " (GenerationUnit#fork), which the baker does not apply even inside the chunk; write through"
                    + " unit.modifier() instead");
        }

        final Palette[] blocks = new Palette[sections];
        final Palette[] biomes = new Palette[sections];
        final List<SynxBlockEntity> blockEntities = new ArrayList<>();
        for (int i = 0; i < sections; i++) {
            blocks[i] = generated[i].blocks();
            biomes[i] = generated[i].biomes();
            for (Int2ObjectMap.Entry<Block> special : generated[i].specials().int2ObjectEntrySet()) {
                final Block block = special.getValue();
                final BlockHandler handler = block.handler();
                if (block.blockEntityType() == null && handler == null) continue;
                final int index = special.getIntKey();
                final int x = CoordConversion.chunkBlockIndexGetX(index);
                final int y = CoordConversion.chunkBlockIndexGetY(index);
                final int z = CoordConversion.chunkBlockIndexGetZ(index);
                final CompoundBinaryTag nbt = block.nbtOrEmpty();
                final CompoundBinaryTag full = block.blockEntityType() == null ? nbt
                        : nbt.putString("id", block.blockEntityType().key().asString());
                blockEntities.add(new SynxBlockEntity(minSection + i, SynxBlockEntity.index(x, y, z),
                        handler == null ? "" : handler.getKey().asString(), full));
            }
        }
        // A generator has no static entities, no light of its own and no data markers
        return new SourceChunk(chunkX, chunkZ, blocks, biomes, blockEntities, List.of(), false, null, null, Set.of());
    }

    @Override
    public void close() {
    }
}
