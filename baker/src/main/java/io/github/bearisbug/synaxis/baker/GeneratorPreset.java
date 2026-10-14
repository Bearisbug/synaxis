package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.instance.generator.Generator;
import io.github.bearisbug.synaxis.synx.SynxManifest;

/**
 * A generator the baker can bake from, with the world it generates into.
 *
 * @param generator the engine generator. The baker runs it once per read of a chunk: several times per chunk
 *                  (the light ring of each neighbouring region and the verification's 3×3 re-bake) and from
 *                  several threads at once, so it must be thread-safe and a pure function of the chunk and the
 *                  seed, as {@link SyntheticTerrain} is; it may not call fork, not even for blocks inside the chunk
 * @param minY      the lowest block y of the world, a multiple of 16
 * @param height    the world height in blocks, a multiple of 16 from 16 to 256
 * @param bounds    the chunks of the map, inclusive
 */
public record GeneratorPreset(Generator generator, int minY, int height, SynxManifest.Bounds bounds) {
}
