package io.github.bearisbug.synaxis.entity.metadata;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.MetadataDef;
import io.github.bearisbug.synaxis.entity.MetadataHolder;
import org.jetbrains.annotations.Nullable;

public class MobMeta extends LivingEntityMeta {
    protected MobMeta(@Nullable Entity entity, MetadataHolder metadata) {
        super(entity, metadata);
    }

    public boolean isNoAi() {
        return metadata.get(MetadataDef.Mob.NO_AI);
    }

    public void setNoAi(boolean value) {
        metadata.set(MetadataDef.Mob.NO_AI, value);
    }

    public boolean isLeftHanded() {
        return metadata.get(MetadataDef.Mob.IS_LEFT_HANDED);
    }

    public void setLeftHanded(boolean value) {
        metadata.set(MetadataDef.Mob.IS_LEFT_HANDED, value);
    }

    public boolean isAggressive() {
        return metadata.get(MetadataDef.Mob.IS_AGGRESSIVE);
    }

    public void setAggressive(boolean value) {
        metadata.set(MetadataDef.Mob.IS_AGGRESSIVE, value);
    }

}
