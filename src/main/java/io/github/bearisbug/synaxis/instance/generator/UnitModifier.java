package io.github.bearisbug.synaxis.instance.generator;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.world.biome.Biome;

/**
 * Writes the blocks and biomes of a {@link GenerationUnit}. {@code setBlock} and {@code setBiome} take
 * absolute coordinates: a chunk or area unit, and a fork, throws {@link IllegalArgumentException} for a
 * point outside it, while the unit of a single section ({@link GenerationUnit#subdivide()}) keeps only the
 * low 4 bits of each coordinate.
 */
public interface UnitModifier extends Block.Setter, Biome.Setter {
    /**
     * Sets the block relative to the absolute start of the unit, each coordinate in [0, size) of its axis.
     *
     * @param x     the x coordinate
     * @param y     the y coordinate
     * @param z     the z coordinate
     * @param block the block to set
     * @throws IllegalArgumentException if the coordinates are outside the unit
     */
    void setRelative(int x, int y, int z, Block block);

    /**
     * Sets all blocks within the unit to the block given by the supplier, which takes absolute coordinates.
     *
     * @param supplier the supplier of the block to set
     */
    void setAll(Supplier supplier);

    /**
     * Sets all blocks within the unit to the block given by the supplier, which takes coordinates relative to
     * the absolute start of the unit, in [0, size) on each axis.
     *
     * @param supplier the supplier of the block to set
     */
    void setAllRelative(Supplier supplier);

    /**
     * Fills the unit with the given block.
     *
     * @param block the block to fill
     */
    void fill(Block block);

    /**
     * Fills [start, end) with the given block, an area inside the unit.
     * <p>
     * A chunk or area unit, and a fork, throws {@link IllegalArgumentException} once it reaches a point
     * of the area outside the unit, keeping the blocks set before it; the unit of a single section fills
     * the part of the area inside it and ignores the rest.
     *
     * @param start the start (min) point of the area
     * @param end   the end (max, exclusive) point of the area
     * @param block the block to fill
     */
    void fill(Point start, Point end, Block block);

    /**
     * Fills every column of the unit from {@code minHeight} up to {@code maxHeight}, exclusive, with the given
     * block. The part of the range outside the unit is cut off.
     *
     * @param minHeight the lowest y to fill
     * @param maxHeight the y above the highest to fill
     * @param block     the block to fill
     */
    void fillHeight(int minHeight, int maxHeight, Block block);

    /**
     * Sets the biome of the whole unit.
     *
     * @param biome the biome to fill
     * @throws IllegalStateException if the unit is a fork
     */
    void fillBiome(RegistryKey<Biome> biome);

    interface Supplier {
        Block get(int x, int y, int z);
    }
}
