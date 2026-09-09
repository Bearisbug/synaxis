package dev.gameengine.entity.metadata.display;

import dev.gameengine.entity.Entity;
import dev.gameengine.entity.MetadataDef;
import dev.gameengine.entity.MetadataHolder;
import dev.gameengine.instance.block.Block;
import org.jetbrains.annotations.Nullable;

public class BlockDisplayMeta extends AbstractDisplayMeta {
    public BlockDisplayMeta(@Nullable Entity entity, MetadataHolder metadata) {
        super(entity, metadata);
    }

    public Block getBlockStateId() {
        return metadata.get(MetadataDef.BlockDisplay.DISPLAYED_BLOCK_STATE);
    }

    public void setBlockState(Block value) {
        metadata.set(MetadataDef.BlockDisplay.DISPLAYED_BLOCK_STATE, value);
    }
}
