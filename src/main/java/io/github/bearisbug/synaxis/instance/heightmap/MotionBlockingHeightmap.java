package io.github.bearisbug.synaxis.instance.heightmap;

import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.block.Block;

public class MotionBlockingHeightmap extends Heightmap {
    public MotionBlockingHeightmap(Chunk attachedChunk) {
        super(attachedChunk);
    }

    @Override
    protected boolean checkBlock(Block block) {
        return block.blocksMotion() || block.fluid();
    }

    @Override
    public Type type() {
        return Type.MOTION_BLOCKING;
    }
}
