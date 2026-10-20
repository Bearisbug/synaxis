package io.github.bearisbug.synaxis.entity.metadata.projectile;

import io.github.bearisbug.synaxis.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * The meta of a projectile that knows who shot it.
 * <p>
 * Except for {@link FireworkRocketMeta}, which syncs it as entity metadata, the shooter only reaches the clients as
 * the object data of the spawn packet, sent once to each viewer: set it before {@code Entity#setInstance}, a change
 * after the spawn only reaches the viewers that start seeing the projectile afterwards. {@code EntityProjectile} sets
 * it on construction. The shooter is a strong reference, held for as long as the projectile lives.
 */
public interface ProjectileMeta {

    @Nullable
    Entity getShooter();

    void setShooter(@Nullable Entity shooter);

}
