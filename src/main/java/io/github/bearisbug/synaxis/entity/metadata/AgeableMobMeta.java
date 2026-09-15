package io.github.bearisbug.synaxis.entity.metadata;

import io.github.bearisbug.synaxis.collision.BoundingBox;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.MetadataDef;
import io.github.bearisbug.synaxis.entity.MetadataHolder;
import org.jetbrains.annotations.Nullable;

public class AgeableMobMeta extends PathfinderMobMeta {
    protected AgeableMobMeta(@Nullable Entity entity, MetadataHolder metadata) {
        super(entity, metadata);
    }

    public boolean isBaby() {
        return metadata.get(MetadataDef.AgeableMob.IS_BABY);
    }

    public void setBaby(boolean value) {
        if (isBaby() == value) {
            return;
        }
        this.consumeEntity((entity) -> {
            BoundingBox bb = entity.getBoundingBox();
            if (value) {
                double width = bb.width() / 2;
                entity.setBoundingBox(width, bb.height() / 2, width);
            } else {
                double width = bb.width() * 2;
                entity.setBoundingBox(width, bb.height() * 2, width);
            }
        });
        metadata.set(MetadataDef.AgeableMob.IS_BABY, value);
    }

    public boolean isAgeLocked() {
        return metadata.get(MetadataDef.AgeableMob.AGE_LOCKED);
    }

    public void setAgeLocked(boolean value) {
        metadata.set(MetadataDef.AgeableMob.AGE_LOCKED, value);
    }
}
