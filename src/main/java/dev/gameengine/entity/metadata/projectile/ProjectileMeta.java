package dev.gameengine.entity.metadata.projectile;

import dev.gameengine.entity.Entity;
import org.jetbrains.annotations.Nullable;

public interface ProjectileMeta {

    @Nullable
    Entity getShooter();

    void setShooter(@Nullable Entity shooter);

}
