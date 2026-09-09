package net.minestom.server.entity;

import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.metadata.animal.ArmadilloMeta;
import net.minestom.server.entity.metadata.animal.SnifferMeta;
import net.minestom.server.entity.metadata.golem.CopperGolemMeta;
import net.minestom.server.entity.metadata.villager.VillagerMeta;
import net.minestom.server.entity.metadata.water.fish.SalmonMeta;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.player.ResolvableProfile;
import net.minestom.server.particle.Particle;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static net.minestom.server.entity.MetadataDefImpl.bitMask;
import static net.minestom.server.entity.MetadataDefImpl.byteMask;
import static net.minestom.server.entity.MetadataDefImpl.index;

/**
 * List of all entity metadata.
 * <p>
 * Classes must be used (and not interfaces) to enforce loading order.
 * <p>
 * When using this class directly, ensure that you are using fields on the most inner class,
 * for example {@link Player#ENTITY_FLAGS} and not {@link MetadataDef#ENTITY_FLAGS}.
 * You need do this as some classes have different default values.
 */
@SuppressWarnings({"unused", "SpellCheckingInspection"})
public sealed class MetadataDef {
    public static final Entry<Byte> ENTITY_FLAGS = index(0, Metadata::Byte, (byte) 0);
    public static final Entry<Boolean> IS_ON_FIRE = bitMask(0, (byte) 0x01, false);
    public static final Entry<Boolean> IS_CROUCHING = bitMask(0, (byte) 0x02, false);
    public static final Entry<Boolean> IS_SPRINTING = bitMask(0, (byte) 0x08, false);
    public static final Entry<Boolean> IS_SWIMMING = bitMask(0, (byte) 0x10, false);
    public static final Entry<Boolean> IS_INVISIBLE = bitMask(0, (byte) 0x20, false);
    public static final Entry<Boolean> HAS_GLOWING_EFFECT = bitMask(0, (byte) 0x40, false);
    public static final Entry<Boolean> IS_FLYING_WITH_ELYTRA = bitMask(0, (byte) 0x80, false);
    public static final Entry<Integer> AIR_TICKS = index(1, Metadata::VarInt, 300);
    public static final Entry<@Nullable Component> CUSTOM_NAME = index(2, Metadata::OptComponent, null);
    public static final Entry<Boolean> CUSTOM_NAME_VISIBLE = index(3, Metadata::Boolean, false);
    public static final Entry<Boolean> IS_SILENT = index(4, Metadata::Boolean, false);
    public static final Entry<Boolean> HAS_NO_GRAVITY = index(5, Metadata::Boolean, false);
    public static final Entry<EntityPose> POSE = index(6, Metadata::Pose, EntityPose.STANDING);
    public static final Entry<Integer> TICKS_FROZEN = index(7, Metadata::VarInt, 0);

    public static final class Interaction extends MetadataDef {
        public static final Entry<Float> WIDTH = index(0, Metadata::Float, 1f);
        public static final Entry<Float> HEIGHT = index(1, Metadata::Float, 1f);
        public static final Entry<Boolean> RESPONSIVE = index(2, Metadata::Boolean, false);
    }

    public static sealed class Display extends MetadataDef {
        public static final Entry<Integer> INTERPOLATION_DELAY = index(0, Metadata::VarInt, 0);
        public static final Entry<Integer> TRANSFORMATION_INTERPOLATION_DURATION = index(1, Metadata::VarInt, 0);
        public static final Entry<Integer> POSITION_ROTATION_INTERPOLATION_DURATION = index(2, Metadata::VarInt, 0);
        public static final Entry<Point> TRANSLATION = index(3, Metadata::Vector3, Vec.ZERO);
        public static final Entry<Point> SCALE = index(4, Metadata::Vector3, Vec.ONE);
        public static final Entry<float[]> ROTATION_LEFT = index(5, Metadata::Quaternion, new float[]{0, 0, 0, 1});
        public static final Entry<float[]> ROTATION_RIGHT = index(6, Metadata::Quaternion, new float[]{0, 0, 0, 1});
        public static final Entry<Byte> BILLBOARD_CONSTRAINTS = index(7, Metadata::Byte, (byte) 0);
        public static final Entry<Integer> BRIGHTNESS_OVERRIDE = index(8, Metadata::VarInt, -1);
        public static final Entry<Float> VIEW_RANGE = index(9, Metadata::Float, 1f);
        public static final Entry<Float> SHADOW_RADIUS = index(10, Metadata::Float, 0f);
        public static final Entry<Float> SHADOW_STRENGTH = index(11, Metadata::Float, 1f);
        public static final Entry<Float> WIDTH = index(12, Metadata::Float, 0f);
        public static final Entry<Float> HEIGHT = index(13, Metadata::Float, 0f);
        public static final Entry<Integer> GLOW_COLOR_OVERRIDE = index(14, Metadata::VarInt, -1);
    }

    public static final class BlockDisplay extends Display {
        public static final Entry<Block> DISPLAYED_BLOCK_STATE = index(0, Metadata::BlockState, Block.AIR);
    }

    public static final class ItemDisplay extends Display {
        public static final Entry<ItemStack> DISPLAYED_ITEM = index(0, Metadata::ItemStack, ItemStack.AIR);
        public static final Entry<Byte> DISPLAY_TYPE = index(1, Metadata::Byte, (byte) 0);
    }

    public static final class TextDisplay extends Display {
        public static final Entry<Component> TEXT = index(0, Metadata::Component, Component.empty());
        public static final Entry<Integer> LINE_WIDTH = index(1, Metadata::VarInt, 200);
        public static final Entry<Integer> BACKGROUND_COLOR = index(2, Metadata::VarInt, 0x40000000);
        public static final Entry<Byte> TEXT_OPACITY = index(3, Metadata::Byte, (byte) -1);
        public static final Entry<Byte> TEXT_DISPLAY_FLAGS = index(4, Metadata::Byte, (byte) 0);
        public static final Entry<Boolean> HAS_SHADOW = bitMask(4, (byte) 0x01, false);
        public static final Entry<Boolean> IS_SEE_THROUGH = bitMask(4, (byte) 0x02, false);
        public static final Entry<Boolean> USE_DEFAULT_BACKGROUND_COLOR = bitMask(4, (byte) 0x04, false);
        public static final Entry<Byte> ALIGNMENT = byteMask(4, (byte) 0x18, 3, (byte) 0);
        public static final Entry<Boolean> ALIGN_LEFT = bitMask(4, (byte) 0x08, false);
        public static final Entry<Boolean> ALIGN_RIGHT = bitMask(4, (byte) 0x10, false);
    }

    public static final class ThrownItemProjectile extends MetadataDef {
        public static final Entry<ItemStack> ITEM = index(0, Metadata::ItemStack, ItemStack.AIR);
    }

    public static final class EyeOfEnder extends MetadataDef {
        public static final Entry<ItemStack> ITEM = index(0, Metadata::ItemStack, ItemStack.AIR);
    }

    public static sealed class AbstractArrow extends MetadataDef {
        public static final Entry<Byte> ARROW_FLAGS = index(0, Metadata::Byte, (byte) 0);
        public static final Entry<Boolean> IS_CRITICAL = bitMask(0, (byte) 0x01, false);
        public static final Entry<Boolean> IS_NO_CLIP = bitMask(0, (byte) 0x02, false);
        public static final Entry<Byte> PIERCING_LEVEL = index(1, Metadata::Byte, (byte) 0);
        public static final Entry<Boolean> IN_GROUND = index(2, Metadata::Boolean, false);
    }

    public static final class Arrow extends AbstractArrow {
        public static final Entry<Integer> COLOR = index(0, Metadata::VarInt, -1);
    }

    public static final class ThrownTrident extends AbstractArrow {
        public static final Entry<Byte> LOYALTY_LEVEL = index(0, Metadata::Byte, (byte) 0);
        public static final Entry<Boolean> HAS_ENCHANTMENT_GLINT = index(1, Metadata::Boolean, false);
    }

    public static final class AbstractVehicle extends MetadataDef {
        public static final Entry<Integer> SHAKING_POWER = index(0, Metadata::VarInt, 0);
        public static final Entry<Integer> SHAKING_DIRECTION = index(1, Metadata::VarInt, 1);
        public static final Entry<Float> SHAKING_MULTIPLIER = index(2, Metadata::Float, 0f);
    }

    public static final class SmartFireball extends MetadataDef {
        public static final Entry<ItemStack> ITEM = index(0, Metadata::ItemStack, ItemStack.AIR);
    }

    public static final class Fireball extends MetadataDef {
        public static final Entry<ItemStack> ITEM = index(0, Metadata::ItemStack, ItemStack.AIR);
    }

    public static final class WitherSkull extends MetadataDef {
        public static final Entry<Boolean> IS_INVULNERABLE = index(0, Metadata::Boolean, false);
    }

    public static final class FireworkRocketEntity extends MetadataDef {
        public static final Entry<ItemStack> ITEM = index(0, Metadata::ItemStack, ItemStack.AIR);
        public static final Entry<@Nullable Integer> SHOOTER_ENTITY_ID = index(1, Metadata::OptVarInt, null);
        public static final Entry<Boolean> IS_SHOT_AT_ANGLE = index(2, Metadata::Boolean, false);
    }

    public static final class ItemEntity extends MetadataDef {
        public static final Entry<ItemStack> ITEM = index(0, Metadata::ItemStack, ItemStack.AIR);
    }

    public static sealed class LivingEntity extends MetadataDef {
        public static final Entry<Byte> LIVING_ENTITY_FLAGS = index(0, Metadata::Byte, (byte) 0);
        public static final Entry<Boolean> IS_HAND_ACTIVE = bitMask(0, (byte) 0x01, false);
        public static final Entry<Boolean> ACTIVE_HAND = bitMask(0, (byte) 0x02, false);
        public static final Entry<Boolean> IS_RIPTIDE_SPIN_ATTACK = bitMask(0, (byte) 0x04, false);
        public static final Entry<Float> HEALTH = index(1, Metadata::Float, 1f);
        public static final Entry<List<Particle>> POTION_EFFECT_PARTICLES = index(2, Metadata::ParticleList, List.of());
        public static final Entry<Boolean> IS_POTION_EFFECT_AMBIANT = index(3, Metadata::Boolean, false);
        public static final Entry<Integer> NUMBER_OF_ARROWS = index(4, Metadata::VarInt, 0);
        public static final Entry<Integer> NUMBER_OF_BEE_STINGERS = index(5, Metadata::VarInt, 0);
        public static final Entry<@Nullable Point> LOCATION_OF_BED = index(6, Metadata::OptBlockPosition, null);
    }

    public static sealed class Avatar extends LivingEntity {
        public static final Entry<MainHand> MAIN_HAND = index(0, Metadata::MainHand, MainHand.RIGHT);
        public static final Entry<Byte> DISPLAYED_MODEL_PARTS_FLAGS = index(1, Metadata::Byte, (byte) 0);
        public static final Entry<Boolean> IS_CAPE_ENABLED = bitMask(1, (byte) 0x01, false);
        public static final Entry<Boolean> IS_JACKET_ENABLED = bitMask(1, (byte) 0x02, false);
        public static final Entry<Boolean> IS_LEFT_SLEEVE_ENABLED = bitMask(1, (byte) 0x04, false);
        public static final Entry<Boolean> IS_RIGHT_SLEEVE_ENABLED = bitMask(1, (byte) 0x08, false);
        public static final Entry<Boolean> IS_LEFT_PANTS_LEG_ENABLED = bitMask(1, (byte) 0x10, false);
        public static final Entry<Boolean> IS_RIGHT_PANTS_LEG_ENABLED = bitMask(1, (byte) 0x20, false);
        public static final Entry<Boolean> IS_HAT_ENABLED = bitMask(1, (byte) 0x40, false);
    }

    public static final class Player extends Avatar {
        public static final Entry<Float> ADDITIONAL_HEARTS = index(0, Metadata::Float, 0f);
        public static final Entry<Integer> SCORE = index(1, Metadata::VarInt, 0);
        public static final Entry<@Nullable Integer> LEFT_SHOULDER_ENTITY_DATA = index(2, Metadata::OptVarInt, null);
        public static final Entry<@Nullable Integer> RIGHT_SHOULDER_ENTITY_DATA = index(3, Metadata::OptVarInt, null);
    }

    public static final class Mannequin extends Avatar {
        // Maniquens have their model flags with all flags enabled compared to Avatar.
        public static final Entry<Byte> DISPLAYED_MODEL_PARTS_FLAGS = index(-1, Metadata::Byte, (byte) 0x7F);
        public static final Entry<Boolean> IS_CAPE_ENABLED = bitMask(-1, (byte) 0x01, true);
        public static final Entry<Boolean> IS_JACKET_ENABLED = bitMask(-1, (byte) 0x02, true);
        public static final Entry<Boolean> IS_LEFT_SLEEVE_ENABLED = bitMask(-1, (byte) 0x04, true);
        public static final Entry<Boolean> IS_RIGHT_SLEEVE_ENABLED = bitMask(-1, (byte) 0x08, true);
        public static final Entry<Boolean> IS_LEFT_PANTS_LEG_ENABLED = bitMask(-1, (byte) 0x10, true);
        public static final Entry<Boolean> IS_RIGHT_PANTS_LEG_ENABLED = bitMask(-1, (byte) 0x20, true);
        public static final Entry<Boolean> IS_HAT_ENABLED = bitMask(-1, (byte) 0x40, true);
        public static final Entry<ResolvableProfile> PROFILE = index(0, Metadata::ResolvableProfile, ResolvableProfile.EMPTY);
        public static final Entry<Boolean> IMMOVABLE = index(1, Metadata::Boolean, false);
        public static final Entry<@Nullable Component> DESCRIPTION = index(2, Metadata::OptComponent, Component.translatable("entity.minecraft.mannequin.label"));
    }

    public static final class ArmorStand extends LivingEntity {
        public static final Entry<Byte> ARMOR_STAND_FLAGS = index(0, Metadata::Byte, (byte) 0);
        public static final Entry<Boolean> IS_SMALL = bitMask(0, (byte) 0x01, false);
        public static final Entry<Boolean> HAS_ARMS = bitMask(0, (byte) 0x04, false);
        public static final Entry<Boolean> HAS_NO_BASE_PLATE = bitMask(0, (byte) 0x08, false);
        public static final Entry<Boolean> IS_MARKER = bitMask(0, (byte) 0x10, false);
        public static final Entry<Point> HEAD_ROTATION = index(1, Metadata::Rotation, Vec.ZERO);
        public static final Entry<Point> BODY_ROTATION = index(2, Metadata::Rotation, Vec.ZERO);
        public static final Entry<Point> LEFT_ARM_ROTATION = index(3, Metadata::Rotation, new Vec(-10, 0, -10));
        public static final Entry<Point> RIGHT_ARM_ROTATION = index(4, Metadata::Rotation, new Vec(-15, 0, 10));
        public static final Entry<Point> LEFT_LEG_ROTATION = index(5, Metadata::Rotation, new Vec(-1, 0, -1));
        public static final Entry<Point> RIGHT_LEG_ROTATION = index(6, Metadata::Rotation, new Vec(1, 0, 1));
    }

    public static sealed class Mob extends LivingEntity {
        public static final Entry<Byte> MOB_FLAGS = index(0, Metadata::Byte, (byte) 0);
        public static final Entry<Boolean> NO_AI = bitMask(0, (byte) 0x01, false);
        public static final Entry<Boolean> IS_LEFT_HANDED = bitMask(0, (byte) 0x02, false);
        public static final Entry<Boolean> IS_AGGRESSIVE = bitMask(0, (byte) 0x04, false);
    }

    public static final class Armadillo extends AgeableMob {
        public static final Entry<ArmadilloMeta.State> STATE = index(0, Metadata::ArmadilloState,
                ArmadilloMeta.State.IDLE);
    }

    public static sealed class AbstractFish extends Mob {
        public static final Entry<Boolean> FROM_BUCKET = index(0, Metadata::Boolean, false);
    }

    public static final class Salmon extends AbstractFish {
        public static final Entry<Integer> SIZE = index(0, Metadata::VarInt, SalmonMeta.Size.MEDIUM.ordinal());
    }

    public static final class TropicalFish extends AbstractFish {
        public static final Entry<Integer> VARIANT = index(0, Metadata::VarInt, 0);
    }

    public static sealed class AgeableMob extends Mob {
        public static final Entry<Boolean> IS_BABY = index(0, Metadata::Boolean, false);
        public static final Entry<Boolean> AGE_LOCKED = index(1, Metadata::Boolean, false);
    }

    public static final class Sniffer extends AgeableMob {
        public static final Entry<SnifferMeta.State> STATE = index(0, Metadata::SnifferState, SnifferMeta.State.IDLING);
        public static final Entry<Integer> DROP_SEED_AT_TICK = index(1, Metadata::VarInt, 0);
    }

    public static sealed class AbstractHorse extends AgeableMob {
        public static final Entry<Byte> ABSTRACT_HORSE_FLAGS = index(0, Metadata::Byte, (byte) 0);
        public static final Entry<Boolean> IS_TAME = bitMask(0, (byte) 0x02, false);
        // 0x04 is unused, historically was for saddle
        public static final Entry<Boolean> HAS_BRED = bitMask(0, (byte) 0x08, false);
        public static final Entry<Boolean> IS_EATING = bitMask(0, (byte) 0x10, false);
        public static final Entry<Boolean> IS_REARING = bitMask(0, (byte) 0x20, false);
        public static final Entry<Boolean> IS_MOUTH_OPEN = bitMask(0, (byte) 0x40, false);
    }

    public static final class Horse extends AbstractHorse {
        public static final Entry<Integer> VARIANT = index(0, Metadata::VarInt, 0);
    }

    public static sealed class ChestedHorse extends AbstractHorse {
        public static final Entry<Boolean> HAS_CHEST = index(0, Metadata::Boolean, false);
    }

    public static final class Llama extends ChestedHorse {
        public static final Entry<Integer> STRENGTH = index(0, Metadata::VarInt, 0);
        public static final Entry<Integer> VARIANT = index(1, Metadata::VarInt, 0);
    }

    public static final class Axolotl extends AgeableMob {
        public static final Entry<Integer> VARIANT = index(0, Metadata::VarInt, 0);
        public static final Entry<Boolean> IS_PLAYING_DEAD = index(1, Metadata::Boolean, false);
        public static final Entry<Boolean> IS_FROM_BUCKET = index(2, Metadata::Boolean, false);
    }

    public static final class Fox extends AgeableMob {
        public static final Entry<Integer> VARIANT = index(0, Metadata::VarInt, 0);
        public static final Entry<Byte> FOX_FLAGS = index(1, Metadata::Byte, (byte) 0);
        public static final Entry<Boolean> IS_SITTING = bitMask(1, (byte) 0x01, false);
        public static final Entry<Boolean> IS_CROUCHING = bitMask(1, (byte) 0x04, false);
        public static final Entry<Boolean> IS_INTERESTED = bitMask(1, (byte) 0x08, false);
        public static final Entry<Boolean> IS_POUNCING = bitMask(1, (byte) 0x10, false);
        public static final Entry<Boolean> IS_SLEEPING = bitMask(1, (byte) 0x20, false);
        public static final Entry<Boolean> IS_FACEPLANTED = bitMask(1, (byte) 0x40, false);
        public static final Entry<Boolean> IS_DEFENDING = bitMask(1, (byte) 0x80, false);
        public static final Entry<@Nullable UUID> FIRST_UUID = index(2, Metadata::OptUUID, null);
        public static final Entry<@Nullable UUID> SECOND_UUID = index(3, Metadata::OptUUID, null);
    }

    public static final class Rabbit extends AgeableMob {
        public static final Entry<Integer> TYPE = index(0, Metadata::VarInt, 0);
    }

    public static final class Mooshroom extends AgeableMob {
        public static final Entry<Integer> VARIANT = index(0, Metadata::VarInt, 0);
    }

    public static sealed class TameableAnimal extends AgeableMob {
        public static final Entry<Byte> TAMEABLE_ANIMAL_FLAGS = index(0, Metadata::Byte, (byte) 0);
        public static final Entry<Boolean> IS_SITTING = bitMask(0, (byte) 0x01, false);
        public static final Entry<Boolean> IS_TAMED = bitMask(0, (byte) 0x04, false);
        public static final Entry<@Nullable UUID> OWNER = index(1, Metadata::OptUUID, null);
    }

    public static final class Parrot extends TameableAnimal {
        public static final Entry<Integer> VARIANT = index(0, Metadata::VarInt, 0);
    }

    public static sealed class AbstractVillager extends AgeableMob {
        public static final Entry<Integer> HEAD_SHAKE_TIMER = index(0, Metadata::VarInt, 0);
    }

    public static final class Villager extends AbstractVillager {
        public static final Entry<VillagerMeta.VillagerData> VARIANT = index(0, Metadata::VillagerData,
                VillagerMeta.VillagerData.DEFAULT);
        public static final Entry<Boolean> IS_FINALIZED = index(1, Metadata::Boolean, false);
    }

    public static final class CopperGolem extends Mob {
        public static final Entry<CopperGolemMeta.WeatherState> WEATHER_STATE = index(0, Metadata::WeatherState,
                CopperGolemMeta.WeatherState.UNAFFECTED);
        public static final Entry<CopperGolemMeta.State> STATE = index(1, Metadata::CopperGolemState,
                CopperGolemMeta.State.IDLE);
    }

    public static final class Wither extends Mob {
        public static final Entry<Integer> CENTER_HEAD_TARGET = index(0, Metadata::VarInt, 0);
        public static final Entry<Integer> LEFT_HEAD_TARGET = index(1, Metadata::VarInt, 0);
        public static final Entry<Integer> RIGHT_HEAD_TARGET = index(2, Metadata::VarInt, 0);
        public static final Entry<Integer> INVULNERABLE_TIME = index(3, Metadata::VarInt, 0);
    }

    /**
     * Get the number of metadata entries for a specific class.
     * <p>
     * Useful if you want to pre-allocate the metadata array.
     */
    public static <T extends MetadataDef> int count(Class<T> clazz) {
        return MetadataDefImpl.count(clazz);
    }

    public sealed interface Entry<T extends @UnknownNullability Object> {
        int index();

        T defaultValue();

        record Index<T extends @UnknownNullability Object>(int index, Function<T, Metadata.Entry<T>> function,
                                                           T defaultValue) implements Entry<T> {
        }

        /**
         * A single bit of a byte-typed metadata entry.
         *
         * @param containerDefault the default value of the whole byte this mask lives in, used as the
         *                         seed when the byte has not been written yet
         */
        record BitMask(int index, byte bitMask, Boolean defaultValue, byte containerDefault) implements Entry<Boolean> {
        }

        /**
         * A sub-range of bits of a byte-typed metadata entry.
         *
         * @param containerDefault the default value of the whole byte this mask lives in, used as the
         *                         seed when the byte has not been written yet
         */
        record ByteMask(int index, byte byteMask, int offset, Byte defaultValue,
                        byte containerDefault) implements Entry<Byte> {
        }
    }
}
