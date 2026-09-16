package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.generator.GenerationUnit;
import io.github.bearisbug.synaxis.instance.generator.Generator;

/**
 * Turns a {@link MapTerrain} into blocks, chunk by chunk, with the engine generator the instance
 * already drives (REQ-M2-010). Nothing is read from or written to disk.
 */
final class MapGenerator implements Generator {

    private final MapTerrain terrain;

    MapGenerator(MapTerrain terrain) {
        this.terrain = terrain;
    }

    @Override
    public void generate(GenerationUnit unit) {
        final BlockVec start = unit.absoluteStart();
        final BlockVec size = unit.size();
        final int minX = start.blockX();
        final int minZ = start.blockZ();
        if (!terrain.preset().contains(minX, minZ)) return; // Outside the map, nothing to build

        unit.modifier().fillHeight(0, MapTerrain.GROUND_HEIGHT, Block.STONE);
        for (int x = minX; x < minX + size.blockX(); x++) {
            for (int z = minZ; z < minZ + size.blockZ(); z++) {
                final int obstacle = terrain.obstacleHeight(x, z);
                for (int y = 0; y < obstacle; y++) {
                    unit.modifier().setBlock(x, MapTerrain.GROUND_HEIGHT + y, z, Block.STONE);
                }
            }
        }
    }
}
