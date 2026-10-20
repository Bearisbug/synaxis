package io.github.bearisbug.synaxis.instance.generator;

import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.instance.block.Block;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Represents an area that can be generated.
 * <p>
 * The size is guaranteed to be a multiple of 16 (section).
 */
public interface GenerationUnit {
    /**
     * This unit's modifier, used to place blocks and biomes within this unit.
     *
     * @return the modifier
     */
    UnitModifier modifier();

    /**
     * The size of this unit in blocks.
     * <p>
     * Guaranteed to be a multiple of 16.
     *
     * @return the size of this unit
     */
    BlockVec size();

    /**
     * The absolute start (min x, y, z) of this unit.
     *
     * @return the absolute start
     */
    BlockVec absoluteStart();

    /**
     * The absolute end of this unit, the first x, y and z after it: the unit covers
     * [{@link #absoluteStart()}, {@code absoluteEnd()}) on each axis.
     *
     * @return the absolute end, exclusive
     */
    BlockVec absoluteEnd();

    /**
     * Creates a fork covering [start, end) rounded out to whole sections, which may reach beyond this unit.
     * <p>
     * A fork is valid during {@link Generator#generate(GenerationUnit)} only: a fork made once it returned
     * is not applied, and writing to a fork after that has no defined result. Once it returned, the blocks
     * a fork set overwrite those of a chunk the instance holds, and its viewers get them; for a chunk the
     * instance does not hold they wait until it is cached, generated or read by the {@code ChunkLoader}. A
     * block the fork did not set keeps the block of the chunk. A fork cannot set biomes, {@code setBiome}
     * and {@code fillBiome} throw {@link IllegalStateException}, and its sections outside the height of the
     * world are dropped with a WARN. The baker fails the region of a generator that forks
     * (docs/DESIGN-M3.md API-M3-003).
     *
     * @param start the start of the fork
     * @param end   the end of the fork, exclusive
     * @return the fork
     * @throws IllegalArgumentException if the area rounded out to whole sections is empty on an axis
     */
    GenerationUnit fork(Point start, Point end);

    /**
     * Creates a fork covering the box of sections around the blocks the consumer sets, wherever they are,
     * applied as {@link #fork(Point, Point)} describes. Nothing is forked when the consumer sets no block.
     *
     * @param consumer the consumer
     */
    void fork(Consumer<Block.Setter> consumer);

    /**
     * Divides this unit into the smallest independent units.
     * <p>
     * A fork made on one of them is applied like a fork made on this unit. The modifier of a single
     * section keeps only the low 4 bits of the coordinates given to {@code setBlock} and {@code setBiome}
     * ({@link UnitModifier}).
     *
     * @return an immutable list of independent units
     */
    default List<GenerationUnit> subdivide() {
        return List.of(this);
    }

    /**
     * Returns the sections that this unit contains. Coordinates are in section coordinates.
     *
     * @return the contained sections
     */
    default Set<BlockVec> sections() {
        final Point start = absoluteStart(), end = absoluteEnd();
        final int minX = start.sectionX(), minY = start.sectionY(), minZ = start.sectionZ();
        final int maxX = end.sectionX(), maxY = end.sectionY(), maxZ = end.sectionZ();
        final int count = (maxX - minX) * (maxY - minY) * (maxZ - minZ);
        BlockVec[] sections = new BlockVec[count];
        int index = 0;
        for (int sectionX = minX; sectionX < maxX; sectionX++) {
            for (int sectionY = minY; sectionY < maxY; sectionY++) {
                for (int sectionZ = minZ; sectionZ < maxZ; sectionZ++) {
                    sections[index++] = new BlockVec(sectionX, sectionY, sectionZ);
                }
            }
        }
        return Set.of(sections);
    }
}
