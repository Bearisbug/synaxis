package io.github.bearisbug.synaxis.entity.metadata.projectile;

import io.github.bearisbug.synaxis.entity.Entity;
import org.jetbrains.annotations.Nullable;

public interface ProjectileMeta {

    @Nullable
    Entity getShooter();

    void setShooter(@Nullable Entity shooter);

}
