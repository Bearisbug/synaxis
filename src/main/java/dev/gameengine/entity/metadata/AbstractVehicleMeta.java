package dev.gameengine.entity.metadata;

import dev.gameengine.entity.Entity;
import dev.gameengine.entity.MetadataDef;
import dev.gameengine.entity.MetadataHolder;
import org.jetbrains.annotations.Nullable;

public class AbstractVehicleMeta extends EntityMeta {
    public AbstractVehicleMeta(@Nullable Entity entity, MetadataHolder metadata) {
        super(entity, metadata);
    }

    public int getShakingTicks() {
        return metadata.get(MetadataDef.AbstractVehicle.SHAKING_POWER);
    }

    public void setShakingTicks(int value) {
        metadata.set(MetadataDef.AbstractVehicle.SHAKING_POWER, value);
    }

    public int getShakingDirection() {
        return metadata.get(MetadataDef.AbstractVehicle.SHAKING_DIRECTION);
    }

    public void setShakingDirection(int value) {
        metadata.set(MetadataDef.AbstractVehicle.SHAKING_DIRECTION, value);
    }

    public float getShakingMultiplier() {
        return metadata.get(MetadataDef.AbstractVehicle.SHAKING_MULTIPLIER);
    }

    public void setShakingMultiplier(float value) {
        metadata.set(MetadataDef.AbstractVehicle.SHAKING_MULTIPLIER, value);
    }
}
