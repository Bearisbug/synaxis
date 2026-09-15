package io.github.bearisbug.synaxis.entity.metadata.display;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.MetadataDef;
import io.github.bearisbug.synaxis.entity.MetadataHolder;
import io.github.bearisbug.synaxis.instance.block.Block;
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
