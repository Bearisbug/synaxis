package io.github.bearisbug.synaxis.registry;

import net.kyori.adventure.nbt.BinaryTag;
import io.github.bearisbug.synaxis.FeatureFlag;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.VillagerProfession;
import io.github.bearisbug.synaxis.entity.attribute.Attribute;
import io.github.bearisbug.synaxis.entity.damage.DamageType;
import io.github.bearisbug.synaxis.gameevent.GameEvent;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockEntityType;
import io.github.bearisbug.synaxis.instance.block.BlockSoundType;
import io.github.bearisbug.synaxis.instance.fluid.Fluid;
import io.github.bearisbug.synaxis.instance.gamerule.GameRule;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.message.ChatType;
import io.github.bearisbug.synaxis.particle.Particle;
import io.github.bearisbug.synaxis.potion.PotionEffect;
import io.github.bearisbug.synaxis.potion.PotionType;
import io.github.bearisbug.synaxis.sound.BuiltinSoundEvent;
import io.github.bearisbug.synaxis.world.DimensionType;
import io.github.bearisbug.synaxis.world.biome.Biome;
import org.jetbrains.annotations.ApiStatus;

/**
 * Contains keys for all built-in Minecraft registries.
 * <br>
 * 原为生成物，自 REQ-008 起作为普通源码维护；重新生成的方法见 `docs/ENGINE.md` 升版本 checklist。
 */
@ApiStatus.NonExtendable
@SuppressWarnings("unused")
public interface BuiltinRegistries {
    /**
     * The registry key for {@link Attribute}.
     */
    RegistryKey<Registry<Attribute>> ATTRIBUTE = RegistryKey.unsafeOf("attribute");

    /**
     * The registry key for the opaque {@code minecraft:banner_pattern} registry.
     */
    RegistryKey<Registry<BinaryTag>> BANNER_PATTERN = RegistryKey.unsafeOf("banner_pattern");

    /**
     * The registry key for {@link Block}.
     */
    RegistryKey<Registry<Block>> BLOCK = RegistryKey.unsafeOf("block");

    /**
     * The registry key for {@link BlockEntityType}.
     */
    RegistryKey<Registry<BlockEntityType>> BLOCK_ENTITY_TYPE = RegistryKey.unsafeOf("block_entity_type");

    /**
     * The registry key for {@link BlockSoundType}.
     */
    RegistryKey<Registry<BlockSoundType>> BLOCK_SOUND_TYPE = RegistryKey.unsafeOf("block_sound_type");

    /**
     * The registry key for the opaque {@code minecraft:cat_sound_variant} registry.
     */
    RegistryKey<Registry<BinaryTag>> CAT_SOUND_VARIANT = RegistryKey.unsafeOf("cat_sound_variant");

    /**
     * The registry key for the opaque {@code minecraft:cat_variant} registry.
     */
    RegistryKey<Registry<BinaryTag>> CAT_VARIANT = RegistryKey.unsafeOf("cat_variant");

    /**
     * The registry key for {@link ChatType}.
     */
    RegistryKey<Registry<ChatType>> CHAT_TYPE = RegistryKey.unsafeOf("chat_type");

    /**
     * The registry key for the opaque {@code minecraft:chicken_sound_variant} registry.
     */
    RegistryKey<Registry<BinaryTag>> CHICKEN_SOUND_VARIANT = RegistryKey.unsafeOf("chicken_sound_variant");

    /**
     * The registry key for the opaque {@code minecraft:chicken_variant} registry.
     */
    RegistryKey<Registry<BinaryTag>> CHICKEN_VARIANT = RegistryKey.unsafeOf("chicken_variant");

    /**
     * The registry key for the opaque {@code minecraft:cow_sound_variant} registry.
     */
    RegistryKey<Registry<BinaryTag>> COW_SOUND_VARIANT = RegistryKey.unsafeOf("cow_sound_variant");

    /**
     * The registry key for the opaque {@code minecraft:cow_variant} registry.
     */
    RegistryKey<Registry<BinaryTag>> COW_VARIANT = RegistryKey.unsafeOf("cow_variant");

    /**
     * The registry key for {@link DamageType}.
     */
    RegistryKey<Registry<DamageType>> DAMAGE_TYPE = RegistryKey.unsafeOf("damage_type");

    /**
     * The registry key for the opaque {@code minecraft:dialog} registry.
     */
    RegistryKey<Registry<BinaryTag>> DIALOG = RegistryKey.unsafeOf("dialog");

    /**
     * The registry key for {@link DimensionType}.
     */
    RegistryKey<Registry<DimensionType>> DIMENSION_TYPE = RegistryKey.unsafeOf("dimension_type");

    /**
     * The registry key for the opaque {@code minecraft:enchantment} registry.
     */
    RegistryKey<Registry<BinaryTag>> ENCHANTMENT = RegistryKey.unsafeOf("enchantment");

    /**
     * The registry key for {@link EntityType}.
     */
    RegistryKey<Registry<EntityType>> ENTITY_TYPE = RegistryKey.unsafeOf("entity_type");

    /**
     * The registry key for {@link FeatureFlag}.
     */
    RegistryKey<Registry<FeatureFlag>> FEATURE_FLAG = RegistryKey.unsafeOf("feature_flag");

    /**
     * The registry key for {@link Fluid}.
     */
    RegistryKey<Registry<Fluid>> FLUID = RegistryKey.unsafeOf("fluid");

    /**
     * The registry key for the opaque {@code minecraft:frog_variant} registry.
     */
    RegistryKey<Registry<BinaryTag>> FROG_VARIANT = RegistryKey.unsafeOf("frog_variant");

    /**
     * The registry key for {@link GameEvent}.
     */
    RegistryKey<Registry<GameEvent>> GAME_EVENT = RegistryKey.unsafeOf("game_event");

    /**
     * The registry key for the opaque {@code minecraft:instrument} registry.
     */
    RegistryKey<Registry<BinaryTag>> INSTRUMENT = RegistryKey.unsafeOf("instrument");

    /**
     * The registry key for {@link Material}.
     */
    RegistryKey<Registry<Material>> ITEM = RegistryKey.unsafeOf("item");

    /**
     * The registry key for the opaque {@code minecraft:jukebox_song} registry.
     */
    RegistryKey<Registry<BinaryTag>> JUKEBOX_SONG = RegistryKey.unsafeOf("jukebox_song");

    /**
     * The registry key for {@link PotionEffect}.
     */
    RegistryKey<Registry<PotionEffect>> MOB_EFFECT = RegistryKey.unsafeOf("mob_effect");

    /**
     * The registry key for the opaque {@code minecraft:painting_variant} registry.
     */
    RegistryKey<Registry<BinaryTag>> PAINTING_VARIANT = RegistryKey.unsafeOf("painting_variant");

    /**
     * The registry key for the opaque {@code minecraft:pig_sound_variant} registry.
     */
    RegistryKey<Registry<BinaryTag>> PIG_SOUND_VARIANT = RegistryKey.unsafeOf("pig_sound_variant");

    /**
     * The registry key for the opaque {@code minecraft:pig_variant} registry.
     */
    RegistryKey<Registry<BinaryTag>> PIG_VARIANT = RegistryKey.unsafeOf("pig_variant");

    /**
     * The registry key for {@link PotionType}.
     */
    RegistryKey<Registry<PotionType>> POTION = RegistryKey.unsafeOf("potion");

    /**
     * The registry key for {@link BuiltinSoundEvent}.
     */
    RegistryKey<Registry<BuiltinSoundEvent>> SOUND_EVENT = RegistryKey.unsafeOf("sound_event");

    /**
     * The registry key for the opaque {@code minecraft:sulfur_cube_archetype} registry.
     */
    RegistryKey<Registry<BinaryTag>> SULFUR_CUBE_ARCHETYPE = RegistryKey.unsafeOf("sulfur_cube_archetype");

    /**
     * The registry key for the opaque {@code minecraft:timeline} registry.
     */
    RegistryKey<Registry<BinaryTag>> TIMELINE = RegistryKey.unsafeOf("timeline");

    /**
     * The registry key for the opaque {@code minecraft:trim_material} registry.
     */
    RegistryKey<Registry<BinaryTag>> TRIM_MATERIAL = RegistryKey.unsafeOf("trim_material");

    /**
     * The registry key for the opaque {@code minecraft:trim_pattern} registry.
     */
    RegistryKey<Registry<BinaryTag>> TRIM_PATTERN = RegistryKey.unsafeOf("trim_pattern");

    /**
     * The registry key for {@link VillagerProfession}.
     */
    RegistryKey<Registry<VillagerProfession>> VILLAGER_PROFESSION = RegistryKey.unsafeOf("villager_profession");

    /**
     * The registry key for the opaque {@code minecraft:wolf_sound_variant} registry.
     */
    RegistryKey<Registry<BinaryTag>> WOLF_SOUND_VARIANT = RegistryKey.unsafeOf("wolf_sound_variant");

    /**
     * The registry key for the opaque {@code minecraft:wolf_variant} registry.
     */
    RegistryKey<Registry<BinaryTag>> WOLF_VARIANT = RegistryKey.unsafeOf("wolf_variant");

    /**
     * The registry key for the opaque {@code minecraft:world_clock} registry.
     */
    RegistryKey<Registry<BinaryTag>> WORLD_CLOCK = RegistryKey.unsafeOf("world_clock");

    /**
     * The registry key for {@link Biome}.
     */
    RegistryKey<Registry<Biome>> BIOME = RegistryKey.unsafeOf("worldgen/biome");

    /**
     * Compatibility alias for {@link #BIOME}.
     *
     * @deprecated use {@link #BIOME}
     */
    @Deprecated(
            forRemoval = true
    )
    RegistryKey<Registry<Biome>> WORLDGEN_BIOME = BIOME;

    /**
     * The registry key for the opaque {@code minecraft:zombie_nautilus_variant} registry.
     */
    RegistryKey<Registry<BinaryTag>> ZOMBIE_NAUTILUS_VARIANT = RegistryKey.unsafeOf("zombie_nautilus_variant");

    /**
     * The registry key for {@link Particle}.
     */
    RegistryKey<Registry<Particle>> PARTICLE_TYPE = RegistryKey.unsafeOf("particle_type");

    /**
     * The registry key for {@link GameRule}.
     */
    RegistryKey<Registry<GameRule<?>>> GAME_RULE = RegistryKey.unsafeOf("game_rule");
}
