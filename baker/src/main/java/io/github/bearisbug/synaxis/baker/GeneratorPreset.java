package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.instance.generator.Generator;
import io.github.bearisbug.synaxis.synx.SynxManifest;

/**
 * A generator the baker can bake from, with the world it generates into.
 *
 * @param generator the engine generator, run once per chunk; it may not fork into other chunks
 * @param minY      the lowest block y of the world, a multiple of 16
 * @param height    the world height in blocks, a multiple of 16
 * @param bounds    the chunks of the map, inclusive
 */
public record GeneratorPreset(Generator generator, int minY, int height, SynxManifest.Bounds bounds) {
}
