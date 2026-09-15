package io.github.bearisbug.synaxis.instance.heightmap;

import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.block.Block;

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
