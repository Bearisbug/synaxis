package io.github.bearisbug.synaxis.baker.anvil;

import io.github.bearisbug.synaxis.instance.palette.Palette;
import org.jetbrains.annotations.Nullable;

/**
 * One 16×16×16 section of a source chunk: block states and biomes as engine palettes, plus the
 * vanilla light arrays when the source has them (2,048 bytes each, the source's nibble order).
 *
 * @param sectionY   section coordinate, {@code y >> 4}
 * @param blocks     block state ids
 * @param biomes     biome ids in the registry the world was opened with
 * @param skyLight   the source's {@code SkyLight}, or null when absent or not 2,048 bytes long
 * @param blockLight the source's {@code BlockLight}, or null when absent or not 2,048 bytes long
 */
public record AnvilSection(int sectionY, Palette blocks, Palette biomes,
                           byte @Nullable [] skyLight,
                           byte @Nullable [] blockLight) {
}
