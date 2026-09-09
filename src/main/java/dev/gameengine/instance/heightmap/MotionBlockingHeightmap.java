package dev.gameengine.instance.heightmap;

import dev.gameengine.instance.Chunk;
import dev.gameengine.instance.block.Block;

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
