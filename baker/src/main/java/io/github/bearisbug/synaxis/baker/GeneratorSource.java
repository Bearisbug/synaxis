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
import java.util.Objects;

/**
 * An engine {@link io.github.bearisbug.synaxis.instance.generator.Generator} as a bake source: each chunk is
 * generated on its own, the way an instance generates it, into fresh sections. A generator that forks into
 * other chunks is refused: the baker reads chunks independently and in any order, so a fork would make a
 * chunk depend on which neighbour happened to be generated first.
 */
public final class GeneratorSource implements BakeSource {
    private final GeneratorPreset preset;
    private final Registries registries;

    public GeneratorSource(GeneratorPreset preset, Registries registries) {
        if (preset.minY() % 16 != 0 || preset.height() <= 0 || preset.height() % 16 != 0) {
            throw new IllegalArgumentException("min_y and height must be multiples of 16: " + preset.minY() + ", "
                    + preset.height());
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
     * @throws IllegalStateException if the generator forked into another chunk
     */
    @Override
    public SourceChunk read(int chunkX, int chunkZ) {
        final int sections = preset.height() / 16, minSection = preset.minY() / 16;
        final GeneratorImpl.GenSection[] generated = new GeneratorImpl.GenSection[sections];
        for (int i = 0; i < sections; i++) generated[i] = new GeneratorImpl.GenSection();
        final GeneratorImpl.UnitImpl unit = GeneratorImpl.chunk(registries.biome(), generated, chunkX, minSection, chunkZ);
        preset.generator().generate(unit);
        if (!unit.forks().isEmpty()) {
            throw new IllegalStateException("chunk " + chunkX + ", " + chunkZ + ": the generator forked into other"
                    + " chunks, which the baker does not support");
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
                final CompoundBinaryTag nbt = Objects.requireNonNullElseGet(block.nbt(), CompoundBinaryTag::empty);
                final CompoundBinaryTag full = block.blockEntityType() == null ? nbt
                        : nbt.putString("id", block.blockEntityType().key().asString());
                blockEntities.add(new SynxBlockEntity(minSection + i, SynxBlockEntity.index(x, y, z),
                        handler == null ? "" : handler.getKey().asString(), full));
            }
        }
        return new SourceChunk(chunkX, chunkZ, blocks, biomes, blockEntities, List.of(), false, null, null);
    }

    @Override
    public void close() {
    }
}
