package dev.gameengine.instance.heightmap;

import dev.gameengine.instance.Chunk;
import dev.gameengine.instance.block.Block;

public class WorldSurfaceHeightmap extends Heightmap {
    public WorldSurfaceHeightmap(Chunk attachedChunk) {
        super(attachedChunk);
    }

    @Override
    protected boolean checkBlock(Block block) {
        return !block.air();
    }

    @Override
    public Type type() {
        return Type.WORLD_SURFACE;
    }
}
