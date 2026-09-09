package dev.gameengine.entity.metadata.projectile;

import dev.gameengine.entity.Entity;
import dev.gameengine.entity.MetadataDef;
import dev.gameengine.entity.MetadataHolder;
import org.jetbrains.annotations.Nullable;

public class ThrownTridentMeta extends AbstractArrowMeta {
    public ThrownTridentMeta(@Nullable Entity entity, MetadataHolder metadata) {
        super(entity, metadata);
    }

    public byte getLoyaltyLevel() {
        return metadata.get(MetadataDef.ThrownTrident.LOYALTY_LEVEL);
    }

    public void setLoyaltyLevel(byte value) {
        metadata.set(MetadataDef.ThrownTrident.LOYALTY_LEVEL, value);
    }

    public boolean isHasEnchantmentGlint() {
        return metadata.get(MetadataDef.ThrownTrident.HAS_ENCHANTMENT_GLINT);
    }

    public void setHasEnchantmentGlint(boolean value) {
        metadata.set(MetadataDef.ThrownTrident.HAS_ENCHANTMENT_GLINT, value);
    }

}
