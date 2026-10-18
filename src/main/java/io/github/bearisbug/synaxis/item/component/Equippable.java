package io.github.bearisbug.synaxis.item.component;

import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.EquipmentSlot;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryTag;
import io.github.bearisbug.synaxis.sound.SoundEvent;
import io.github.bearisbug.synaxis.utils.ThrowingFunction;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.Nullable;

public record Equippable(
        EquipmentSlot slot,
        SoundEvent equipSound,
        @Nullable String assetId,
        @Nullable String cameraOverlay,
        @Nullable RegistryTag<EntityType> allowedEntities,
        boolean dispensable,
        boolean swappable,
        boolean damageOnHurt,
        boolean equipOnInteract,
        boolean canBeSheared,
        SoundEvent shearingSound
) {
    public static final SoundEvent DEFAULT_EQUIP_SOUND = SoundEvent.ITEM_ARMOR_EQUIP_GENERIC;
    public static final SoundEvent DEFAULT_SHEARING_SOUND = SoundEvent.ITEM_SHEARS_SNIP;

    public static final NetworkBuffer.Type<Equippable> NETWORK_TYPE = NetworkBufferTemplate.template(
            EquipmentSlot.NETWORK_TYPE, Equippable::slot,
            SoundEvent.NETWORK_TYPE, Equippable::equipSound,
            NetworkBuffer.STRING.optional(), Equippable::assetId,
            NetworkBuffer.STRING.optional(), Equippable::cameraOverlay,
            RegistryTag.networkType(Registries::entityType).optional(), Equippable::allowedEntities,
            NetworkBuffer.BOOLEAN, Equippable::dispensable,
            NetworkBuffer.BOOLEAN, Equippable::swappable,
            NetworkBuffer.BOOLEAN, Equippable::damageOnHurt,
            NetworkBuffer.BOOLEAN, Equippable::equipOnInteract,
            NetworkBuffer.BOOLEAN, Equippable::canBeSheared,
            SoundEvent.NETWORK_TYPE, Equippable::shearingSound,
            Equippable::new);
    // The transform reports a value the constructor refuses as a decode error rather than throwing it out of decode
    public static final Codec<Equippable> CODEC = StructCodec.struct(
            "slot", EquipmentSlot.CODEC, Equippable::slot,
            "equip_sound", SoundEvent.CODEC.optional(DEFAULT_EQUIP_SOUND), Equippable::equipSound,
            "asset_id", Codec.STRING.optional(), Equippable::assetId,
            "camera_overlay", Codec.STRING.optional(), Equippable::cameraOverlay,
            "allowed_entities", RegistryTag.codec(Registries::entityType).optional(), Equippable::allowedEntities,
            "dispensable", Codec.BOOLEAN.optional(true), Equippable::dispensable,
            "swappable", Codec.BOOLEAN.optional(true), Equippable::swappable,
            "damage_on_hurt", Codec.BOOLEAN.optional(true), Equippable::damageOnHurt,
            "equip_on_interact", Codec.BOOLEAN.optional(false), Equippable::equipOnInteract,
            "can_be_sheared", Codec.BOOLEAN.optional(false), Equippable::canBeSheared,
            "shearing_sound", SoundEvent.CODEC.optional(DEFAULT_SHEARING_SOUND), Equippable::shearingSound,
            Equippable::new).transform(ThrowingFunction.identity(), ThrowingFunction.identity());

    // Both are identifiers in 26.2, which a client that cannot parse them drops the connection over
    public Equippable {
        Check.argCondition(assetId != null && !Key.parseable(assetId),
                "assetId must be a namespaced id such as synaxis:knight (a-z 0-9 . _ - /), got: {0}", assetId);
        Check.argCondition(cameraOverlay != null && !Key.parseable(cameraOverlay),
                "cameraOverlay must be a namespaced id such as synaxis:misc/pumpkin_blur (a-z 0-9 . _ - /), got: {0}", cameraOverlay);
    }

    public Equippable withSlot(EquipmentSlot slot) {
        return new Equippable(slot, equipSound, assetId, cameraOverlay, allowedEntities, dispensable, swappable, damageOnHurt, equipOnInteract, canBeSheared, shearingSound);
    }

    public Equippable withEquipSound(SoundEvent equipSound) {
        return new Equippable(slot, equipSound, assetId, cameraOverlay, allowedEntities, dispensable, swappable, damageOnHurt, equipOnInteract, canBeSheared, shearingSound);
    }

    public Equippable withAssetId(@Nullable String assetId) {
        return new Equippable(slot, equipSound, assetId, cameraOverlay, allowedEntities, dispensable, swappable, damageOnHurt, equipOnInteract, canBeSheared, shearingSound);
    }

    public Equippable withCameraOverlay(@Nullable String cameraOverlay) {
        return new Equippable(slot, equipSound, assetId, cameraOverlay, allowedEntities, dispensable, swappable, damageOnHurt, equipOnInteract, canBeSheared, shearingSound);
    }

    public Equippable withAllowedEntities(@Nullable RegistryTag<EntityType> allowedEntities) {
        return new Equippable(slot, equipSound, assetId, cameraOverlay, allowedEntities, dispensable, swappable, damageOnHurt, equipOnInteract, canBeSheared, shearingSound);
    }

    public Equippable withDispensable(boolean dispensable) {
        return new Equippable(slot, equipSound, assetId, cameraOverlay, allowedEntities, dispensable, swappable, damageOnHurt, equipOnInteract, canBeSheared, shearingSound);
    }

    public Equippable withSwappable(boolean swappable) {
        return new Equippable(slot, equipSound, assetId, cameraOverlay, allowedEntities, dispensable, swappable, damageOnHurt, equipOnInteract, canBeSheared, shearingSound);
    }

    public Equippable withDamageOnHurt(boolean damageOnHurt) {
        return new Equippable(slot, equipSound, assetId, cameraOverlay, allowedEntities, dispensable, swappable, damageOnHurt, equipOnInteract, canBeSheared, shearingSound);
    }

    public Equippable withEquipOnInteract(boolean equipOnInteract) {
        return new Equippable(slot, equipSound, assetId, cameraOverlay, allowedEntities, dispensable, swappable, damageOnHurt, equipOnInteract, canBeSheared, shearingSound);
    }

    public Equippable withCanBeSheared(boolean canBeSheared) {
        return new Equippable(slot, equipSound, assetId, cameraOverlay, allowedEntities, dispensable, swappable, damageOnHurt, equipOnInteract, canBeSheared, shearingSound);
    }

    public Equippable withShearingSound(SoundEvent shearingSound) {
        return new Equippable(slot, equipSound, assetId, cameraOverlay, allowedEntities, dispensable, swappable, damageOnHurt, equipOnInteract, canBeSheared, shearingSound);
    }
}
