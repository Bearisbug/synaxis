package io.github.bearisbug.synaxis.component;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.util.RGBLike;
import io.github.bearisbug.synaxis.adventure.ComponentCodecs;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.color.Color;
import io.github.bearisbug.synaxis.color.DyeColor;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.VillagerType;
import io.github.bearisbug.synaxis.entity.damage.DamageType;
import io.github.bearisbug.synaxis.instance.block.BlockEntityType;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.ItemStackTemplate;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.item.component.ArmorTrim;
import io.github.bearisbug.synaxis.item.component.AttackRange;
import io.github.bearisbug.synaxis.item.component.AttributeList;
import io.github.bearisbug.synaxis.item.component.AxolotlVariant;
import io.github.bearisbug.synaxis.item.component.BannerPatterns;
import io.github.bearisbug.synaxis.item.component.Bee;
import io.github.bearisbug.synaxis.item.component.BlockPredicates;
import io.github.bearisbug.synaxis.item.component.BlocksAttacks;
import io.github.bearisbug.synaxis.item.component.Consumable;
import io.github.bearisbug.synaxis.item.component.CustomData;
import io.github.bearisbug.synaxis.item.component.CustomModelData;
import io.github.bearisbug.synaxis.item.component.DamageResistant;
import io.github.bearisbug.synaxis.item.component.DeathProtection;
import io.github.bearisbug.synaxis.item.component.DebugStickState;
import io.github.bearisbug.synaxis.item.component.EnchantmentList;
import io.github.bearisbug.synaxis.item.component.Equippable;
import io.github.bearisbug.synaxis.item.component.FireworkExplosion;
import io.github.bearisbug.synaxis.item.component.FireworkList;
import io.github.bearisbug.synaxis.item.component.Food;
import io.github.bearisbug.synaxis.item.component.FoxVariant;
import io.github.bearisbug.synaxis.item.component.HorseVariant;
import io.github.bearisbug.synaxis.item.component.ItemBlockState;
import io.github.bearisbug.synaxis.item.component.ItemRarity;
import io.github.bearisbug.synaxis.item.component.KineticWeapon;
import io.github.bearisbug.synaxis.item.component.LlamaVariant;
import io.github.bearisbug.synaxis.item.component.LodestoneTracker;
import io.github.bearisbug.synaxis.item.component.MapDecorations;
import io.github.bearisbug.synaxis.item.component.MapPostProcessing;
import io.github.bearisbug.synaxis.item.component.MooshroomVariant;
import io.github.bearisbug.synaxis.item.component.ParrotVariant;
import io.github.bearisbug.synaxis.item.component.PiercingWeapon;
import io.github.bearisbug.synaxis.item.component.PotDecorations;
import io.github.bearisbug.synaxis.item.component.PotionContents;
import io.github.bearisbug.synaxis.item.component.RabbitVariant;
import io.github.bearisbug.synaxis.item.component.SalmonSize;
import io.github.bearisbug.synaxis.item.component.SeededContainerLoot;
import io.github.bearisbug.synaxis.item.component.SuspiciousStewEffects;
import io.github.bearisbug.synaxis.item.component.SwingAnimation;
import io.github.bearisbug.synaxis.item.component.Tool;
import io.github.bearisbug.synaxis.item.component.TooltipDisplay;
import io.github.bearisbug.synaxis.item.component.TropicalFishPattern;
import io.github.bearisbug.synaxis.item.component.TypedCustomData;
import io.github.bearisbug.synaxis.item.component.UseCooldown;
import io.github.bearisbug.synaxis.item.component.UseEffects;
import io.github.bearisbug.synaxis.item.component.Weapon;
import io.github.bearisbug.synaxis.item.component.WritableBookContent;
import io.github.bearisbug.synaxis.item.component.WrittenBookContent;
import io.github.bearisbug.synaxis.item.predicate.ItemPredicate;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.player.ResolvableProfile;
import io.github.bearisbug.synaxis.registry.BuiltinRegistries;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.Registry;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.registry.RegistryTag;
import io.github.bearisbug.synaxis.sound.SoundEvent;
import io.github.bearisbug.synaxis.utils.Unit;
import io.github.bearisbug.synaxis.utils.validate.Check;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;

import static io.github.bearisbug.synaxis.component.DataComponentImpl.register;

public class DataComponents {
    private static final int CONTAINER_SIZE = 256;

    public static final DataComponent<CustomData> CUSTOM_DATA = register("custom_data", CustomData.NETWORK_TYPE, CustomData.CODEC);
    public static final DataComponent<Integer> MAX_STACK_SIZE = register("max_stack_size", NetworkBuffer.VAR_INT, Codec.INT);
    public static final DataComponent<Integer> MAX_DAMAGE = register("max_damage", NetworkBuffer.VAR_INT, Codec.INT);
    public static final DataComponent<Integer> DAMAGE = register("damage", NetworkBuffer.VAR_INT, Codec.INT);
    public static final DataComponent<Unit> UNBREAKABLE = register("unbreakable", NetworkBuffer.UNIT, Codec.UNIT);
    public static final DataComponent<UseEffects> USE_EFFECTS = register("use_effects", UseEffects.NETWORK_TYPE, UseEffects.CODEC);
    public static final DataComponent<Component> CUSTOM_NAME = register("custom_name", NetworkBuffer.COMPONENT, ComponentCodecs.COMPONENT);
    public static final DataComponent<Float> MINIMUM_ATTACK_CHARGE = register("minimum_attack_charge", NetworkBuffer.FLOAT, Codec.FLOAT);
    public static final DataComponent<RegistryKey<DamageType>> DAMAGE_TYPE = register("damage_type", DamageType.NETWORK_TYPE, DamageType.CODEC);
    public static final DataComponent<Component> ITEM_NAME = register("item_name", NetworkBuffer.COMPONENT, ComponentCodecs.COMPONENT);
    public static final DataComponent<String> ITEM_MODEL = register("item_model", NetworkBuffer.STRING, Codec.STRING, identifier("item_model"));
    public static final DataComponent<List<Component>> LORE = register("lore", NetworkBuffer.COMPONENT.list(256), ComponentCodecs.COMPONENT.list(256), lore -> {
        Check.argCondition(lore.size() > 256, "minecraft:lore holds at most 256 lines, got {0}", lore.size());
        return List.copyOf(lore);
    });
    public static final DataComponent<ItemRarity> RARITY = register("rarity", ItemRarity.NETWORK_TYPE, ItemRarity.CODEC);
    public static final DataComponent<EnchantmentList> ENCHANTMENTS = register("enchantments", EnchantmentList.NETWORK_TYPE, EnchantmentList.CODEC);
    public static final DataComponent<BlockPredicates> CAN_PLACE_ON = register("can_place_on", BlockPredicates.NETWORK_TYPE, BlockPredicates.CODEC);
    public static final DataComponent<BlockPredicates> CAN_BREAK = register("can_break", BlockPredicates.NETWORK_TYPE, BlockPredicates.CODEC);
    public static final DataComponent<AttributeList> ATTRIBUTE_MODIFIERS = register("attribute_modifiers", AttributeList.NETWORK_TYPE, AttributeList.CODEC);
    public static final DataComponent<CustomModelData> CUSTOM_MODEL_DATA = register("custom_model_data", CustomModelData.NETWORK_TYPE, CustomModelData.CODEC);
    public static final DataComponent<TooltipDisplay> TOOLTIP_DISPLAY = register("tooltip_display", TooltipDisplay.NETWORK_TYPE, TooltipDisplay.CODEC);
    public static final DataComponent<Integer> REPAIR_COST = register("repair_cost", NetworkBuffer.VAR_INT, Codec.INT);
    public static final DataComponent<Unit> CREATIVE_SLOT_LOCK = register("creative_slot_lock", NetworkBuffer.UNIT, null);
    public static final DataComponent<Boolean> ENCHANTMENT_GLINT_OVERRIDE = register("enchantment_glint_override", NetworkBuffer.BOOLEAN, Codec.BOOLEAN);
    public static final DataComponent<Unit> INTANGIBLE_PROJECTILE = register("intangible_projectile", null, Codec.UNIT);
    public static final DataComponent<Food> FOOD = register("food", Food.NETWORK_TYPE, Food.CODEC);
    public static final DataComponent<Consumable> CONSUMABLE = register("consumable", Consumable.NETWORK_TYPE, Consumable.CODEC);
    public static final DataComponent<ItemStack> USE_REMAINDER = register("use_remainder", ItemStackTemplate.NETWORK_TYPE, ItemStackTemplate.CODEC, notAir("use_remainder"));
    public static final DataComponent<UseCooldown> USE_COOLDOWN = register("use_cooldown", UseCooldown.NETWORK_TYPE, UseCooldown.CODEC);
    public static final DataComponent<DamageResistant> DAMAGE_RESISTANT = register("damage_resistant", DamageResistant.NETWORK_TYPE, DamageResistant.CODEC);
    public static final DataComponent<Tool> TOOL = register("tool", Tool.NETWORK_TYPE, Tool.CODEC);
    public static final DataComponent<Weapon> WEAPON = register("weapon", Weapon.NETWORK_TYPE, Weapon.CODEC);
    public static final DataComponent<AttackRange> ATTACK_RANGE = register("attack_range", AttackRange.NETWORK_TYPE, AttackRange.CODEC);
    public static final DataComponent<Integer> ENCHANTABLE = register("enchantable", NetworkBuffer.VAR_INT, wrapObject("value", Codec.INT));
    public static final DataComponent<Equippable> EQUIPPABLE = register("equippable", Equippable.NETWORK_TYPE, Equippable.CODEC);
    public static final DataComponent<RegistryTag<Material>> REPAIRABLE = register("repairable", RegistryTag.networkType(Registries::material), wrapObject("items", RegistryTag.codec(Registries::material)));
    public static final DataComponent<Unit> GLIDER = register("glider", NetworkBuffer.UNIT, Codec.UNIT);
    public static final DataComponent<String> TOOLTIP_STYLE = register("tooltip_style", NetworkBuffer.STRING, Codec.STRING, identifier("tooltip_style"));
    public static final DataComponent<DeathProtection> DEATH_PROTECTION = register("death_protection", DeathProtection.NETWORK_TYPE, DeathProtection.CODEC);
    public static final DataComponent<BlocksAttacks> BLOCKS_ATTACKS = register("blocks_attacks", BlocksAttacks.NETWORK_TYPE, BlocksAttacks.NBT_TYPE);
    public static final DataComponent<PiercingWeapon> PIERCING_WEAPON = register("piercing_weapon", PiercingWeapon.NETWORK_TYPE, PiercingWeapon.CODEC);
    public static final DataComponent<KineticWeapon> KINETIC_WEAPON = register("kinetic_weapon", KineticWeapon.NETWORK_TYPE, KineticWeapon.CODEC);
    public static final DataComponent<SwingAnimation> SWING_ANIMATION = register("swing_animation", SwingAnimation.NETWORK_TYPE, SwingAnimation.CODEC);
    public static final DataComponent<Integer> ADDITIONAL_TRADE_COST = register("additional_trade_cost", NetworkBuffer.VAR_INT, null);
    public static final DataComponent<EnchantmentList> STORED_ENCHANTMENTS = register("stored_enchantments", EnchantmentList.NETWORK_TYPE, EnchantmentList.CODEC);
    public static final DataComponent<DyeColor> DYE = register("dye", DyeColor.NETWORK_TYPE, DyeColor.CODEC);
    public static final DataComponent<RGBLike> DYED_COLOR = register("dyed_color", Color.NETWORK_TYPE, Color.CODEC);
    public static final DataComponent<RGBLike> MAP_COLOR = register("map_color", Color.NETWORK_TYPE, Color.CODEC);
    public static final DataComponent<Integer> MAP_ID = register("map_id", NetworkBuffer.VAR_INT, Codec.INT);
    public static final DataComponent<MapDecorations> MAP_DECORATIONS = register("map_decorations", null, MapDecorations.CODEC);
    public static final DataComponent<MapPostProcessing> MAP_POST_PROCESSING = register("map_post_processing", MapPostProcessing.NETWORK_TYPE, null);
    public static final DataComponent<List<ItemStack>> CHARGED_PROJECTILES = register("charged_projectiles", ItemStackTemplate.NETWORK_TYPE.list(Short.MAX_VALUE), ItemStackTemplate.CODEC.list(Short.MAX_VALUE), noAir("charged_projectiles"));
    public static final DataComponent<List<ItemStack>> BUNDLE_CONTENTS = register("bundle_contents", ItemStackTemplate.NETWORK_TYPE.list(Short.MAX_VALUE), ItemStackTemplate.CODEC.list(Short.MAX_VALUE), noAir("bundle_contents"));
    public static final DataComponent<PotionContents> POTION_CONTENTS = register("potion_contents", PotionContents.NETWORK_TYPE, PotionContents.CODEC);
    public static final DataComponent<Float> POTION_DURATION_SCALE = register("potion_duration_scale", NetworkBuffer.FLOAT, Codec.FLOAT);
    public static final DataComponent<SuspiciousStewEffects> SUSPICIOUS_STEW_EFFECTS = register("suspicious_stew_effects", SuspiciousStewEffects.NETWORK_TYPE, SuspiciousStewEffects.CODEC);
    public static final DataComponent<WritableBookContent> WRITABLE_BOOK_CONTENT = register("writable_book_content", WritableBookContent.NETWORK_TYPE, WritableBookContent.CODEC);
    public static final DataComponent<WrittenBookContent> WRITTEN_BOOK_CONTENT = register("written_book_content", WrittenBookContent.NETWORK_TYPE, WrittenBookContent.CODEC);
    public static final DataComponent<ArmorTrim> TRIM = register("trim", ArmorTrim.NETWORK_TYPE, ArmorTrim.CODEC);
    public static final DataComponent<DebugStickState> DEBUG_STICK_STATE = register("debug_stick_state", DebugStickState.NETWORK_TYPE, DebugStickState.CODEC);
    public static final DataComponent<TypedCustomData<EntityType>> ENTITY_DATA = register("entity_data", TypedCustomData.networkType(EntityType.NETWORK_TYPE), TypedCustomData.codec(EntityType.CODEC));
    public static final DataComponent<CustomData> BUCKET_ENTITY_DATA = register("bucket_entity_data", CustomData.NETWORK_TYPE, CustomData.CODEC);
    public static final DataComponent<TypedCustomData<BlockEntityType>> BLOCK_ENTITY_DATA = register("block_entity_data", TypedCustomData.networkType(BlockEntityType.NETWORK_TYPE), TypedCustomData.codec(BlockEntityType.CODEC));
    public static final DataComponent<RegistryKey<BinaryTag>> INSTRUMENT = register("instrument", RegistryKey.holderNetworkType(opaque(BuiltinRegistries.INSTRUMENT)), RegistryKey.codec(opaque(BuiltinRegistries.INSTRUMENT)));
    public static final DataComponent<RegistryKey<BinaryTag>> PROVIDES_TRIM_MATERIAL = register("provides_trim_material", RegistryKey.holderNetworkType(opaque(BuiltinRegistries.TRIM_MATERIAL)), RegistryKey.codec(opaque(BuiltinRegistries.TRIM_MATERIAL)));
    public static final DataComponent<Integer> OMINOUS_BOTTLE_AMPLIFIER = register("ominous_bottle_amplifier", NetworkBuffer.VAR_INT, Codec.INT);
    public static final DataComponent<RegistryKey<BinaryTag>> JUKEBOX_PLAYABLE = register("jukebox_playable", RegistryKey.holderNetworkType(opaque(BuiltinRegistries.JUKEBOX_SONG)), RegistryKey.codec(opaque(BuiltinRegistries.JUKEBOX_SONG)));
    public static final DataComponent<RegistryTag<BinaryTag>> PROVIDES_BANNER_PATTERNS = register("provides_banner_patterns", RegistryTag.networkType(opaque(BuiltinRegistries.BANNER_PATTERN)), RegistryTag.codec(opaque(BuiltinRegistries.BANNER_PATTERN)));
    public static final DataComponent<List<String>> RECIPES = register("recipes", NetworkBuffer.STRING.list(Short.MAX_VALUE), Codec.STRING.list(Short.MAX_VALUE), List::copyOf);
    public static final DataComponent<LodestoneTracker> LODESTONE_TRACKER = register("lodestone_tracker", LodestoneTracker.NETWORK_TYPE, LodestoneTracker.CODEC);
    public static final DataComponent<FireworkExplosion> FIREWORK_EXPLOSION = register("firework_explosion", FireworkExplosion.NETWORK_TYPE, FireworkExplosion.CODEC);
    public static final DataComponent<FireworkList> FIREWORKS = register("fireworks", FireworkList.NETWORK_TYPE, FireworkList.NBT_TYPE);
    public static final DataComponent<ResolvableProfile> PROFILE = register("profile", ResolvableProfile.NETWORK_TYPE, ResolvableProfile.CODEC);
    public static final DataComponent<String> NOTE_BLOCK_SOUND = register("note_block_sound", NetworkBuffer.STRING, Codec.STRING, identifier("note_block_sound"));
    public static final DataComponent<BannerPatterns> BANNER_PATTERNS = register("banner_patterns", BannerPatterns.NETWORK_TYPE, BannerPatterns.CODEC);
    public static final DataComponent<DyeColor> BASE_COLOR = register("base_color", DyeColor.NETWORK_TYPE, DyeColor.CODEC);
    public static final DataComponent<PotDecorations> POT_DECORATIONS = register("pot_decorations", PotDecorations.NETWORK_TYPE, PotDecorations.NBT_TYPE);
    public static final DataComponent<List<ItemStack>> CONTAINER = register("container", containerNetworkType(), containerCodec(), List::copyOf);
    public static final DataComponent<ItemBlockState> BLOCK_STATE = register("block_state", ItemBlockState.NETWORK_TYPE, ItemBlockState.CODEC);
    public static final DataComponent<List<Bee>> BEES = register("bees", Bee.NETWORK_TYPE.list(Short.MAX_VALUE), Bee.CODEC.list(), List::copyOf);
    public static final DataComponent<ItemStack> SULFUR_CUBE_CONTENT = register("sulfur_cube_content", ItemStackTemplate.NETWORK_TYPE, ItemStackTemplate.CODEC, notAir("sulfur_cube_content"));
    public static final DataComponent<ItemPredicate> LOCK = register("lock", null, ItemPredicate.CODEC);
    public static final DataComponent<SeededContainerLoot> CONTAINER_LOOT = register("container_loot", null, SeededContainerLoot.CODEC);
    public static final DataComponent<SoundEvent> BREAK_SOUND = register("break_sound", SoundEvent.NETWORK_TYPE, SoundEvent.CODEC);
    public static final DataComponent<VillagerType> VILLAGER_VARIANT = register("villager/variant", VillagerType.NETWORK_TYPE, VillagerType.CODEC);
    public static final DataComponent<RegistryKey<BinaryTag>> WOLF_VARIANT = register("wolf/variant", RegistryKey.networkType(opaque(BuiltinRegistries.WOLF_VARIANT)), RegistryKey.codec(opaque(BuiltinRegistries.WOLF_VARIANT)));
    public static final DataComponent<RegistryKey<BinaryTag>> WOLF_SOUND_VARIANT = register("wolf/sound_variant", RegistryKey.networkType(opaque(BuiltinRegistries.WOLF_SOUND_VARIANT)), RegistryKey.codec(opaque(BuiltinRegistries.WOLF_SOUND_VARIANT)));
    public static final DataComponent<DyeColor> WOLF_COLLAR = register("wolf/collar", DyeColor.NETWORK_TYPE, DyeColor.CODEC);
    public static final DataComponent<FoxVariant> FOX_VARIANT = register("fox/variant", FoxVariant.NETWORK_TYPE, FoxVariant.CODEC);
    public static final DataComponent<SalmonSize> SALMON_SIZE = register("salmon/size", SalmonSize.NETWORK_TYPE, SalmonSize.CODEC);
    public static final DataComponent<ParrotVariant> PARROT_VARIANT = register("parrot/variant", ParrotVariant.NETWORK_TYPE, ParrotVariant.CODEC);
    public static final DataComponent<TropicalFishPattern> TROPICAL_FISH_PATTERN = register("tropical_fish/pattern", TropicalFishPattern.NETWORK_TYPE, TropicalFishPattern.CODEC);
    public static final DataComponent<DyeColor> TROPICAL_FISH_BASE_COLOR = register("tropical_fish/base_color", DyeColor.NETWORK_TYPE, DyeColor.CODEC);
    public static final DataComponent<DyeColor> TROPICAL_FISH_PATTERN_COLOR = register("tropical_fish/pattern_color", DyeColor.NETWORK_TYPE, DyeColor.CODEC);
    public static final DataComponent<MooshroomVariant> MOOSHROOM_VARIANT = register("mooshroom/variant", MooshroomVariant.NETWORK_TYPE, MooshroomVariant.CODEC);
    public static final DataComponent<RabbitVariant> RABBIT_VARIANT = register("rabbit/variant", RabbitVariant.NETWORK_TYPE, RabbitVariant.CODEC);
    public static final DataComponent<RegistryKey<BinaryTag>> PIG_VARIANT = register("pig/variant", RegistryKey.networkType(opaque(BuiltinRegistries.PIG_VARIANT)), RegistryKey.codec(opaque(BuiltinRegistries.PIG_VARIANT)));
    public static final DataComponent<RegistryKey<BinaryTag>> PIG_SOUND_VARIANT = register("pig/sound_variant", RegistryKey.networkType(opaque(BuiltinRegistries.PIG_SOUND_VARIANT)), RegistryKey.codec(opaque(BuiltinRegistries.PIG_SOUND_VARIANT)));
    public static final DataComponent<RegistryKey<BinaryTag>> COW_VARIANT = register("cow/variant", RegistryKey.networkType(opaque(BuiltinRegistries.COW_VARIANT)), RegistryKey.codec(opaque(BuiltinRegistries.COW_VARIANT)));
    public static final DataComponent<RegistryKey<BinaryTag>> COW_SOUND_VARIANT = register("cow/sound_variant", RegistryKey.networkType(opaque(BuiltinRegistries.COW_SOUND_VARIANT)), RegistryKey.codec(opaque(BuiltinRegistries.COW_SOUND_VARIANT)));
    public static final DataComponent<RegistryKey<BinaryTag>> CHICKEN_VARIANT = register("chicken/variant", RegistryKey.networkType(opaque(BuiltinRegistries.CHICKEN_VARIANT)), RegistryKey.codec(opaque(BuiltinRegistries.CHICKEN_VARIANT)));
    public static final DataComponent<RegistryKey<BinaryTag>> CHICKEN_SOUND_VARIANT = register("chicken/sound_variant", RegistryKey.networkType(opaque(BuiltinRegistries.CHICKEN_SOUND_VARIANT)), RegistryKey.codec(opaque(BuiltinRegistries.CHICKEN_SOUND_VARIANT)));
    public static final DataComponent<RegistryKey<BinaryTag>> ZOMBIE_NAUTILUS_VARIANT = register("zombie_nautilus/variant", RegistryKey.networkType(opaque(BuiltinRegistries.ZOMBIE_NAUTILUS_VARIANT)), RegistryKey.codec(opaque(BuiltinRegistries.ZOMBIE_NAUTILUS_VARIANT)));
    public static final DataComponent<RegistryKey<BinaryTag>> FROG_VARIANT = register("frog/variant", RegistryKey.networkType(opaque(BuiltinRegistries.FROG_VARIANT)), RegistryKey.codec(opaque(BuiltinRegistries.FROG_VARIANT)));
    public static final DataComponent<HorseVariant> HORSE_VARIANT = register("horse/variant", HorseVariant.NETWORK_TYPE, HorseVariant.CODEC);
    public static final DataComponent<RegistryKey<BinaryTag>> PAINTING_VARIANT = register("painting/variant", RegistryKey.holderNetworkType(opaque(BuiltinRegistries.PAINTING_VARIANT)), RegistryKey.codec(opaque(BuiltinRegistries.PAINTING_VARIANT)));
    public static final DataComponent<LlamaVariant> LLAMA_VARIANT = register("llama/variant", LlamaVariant.NETWORK_TYPE, LlamaVariant.CODEC);
    public static final DataComponent<AxolotlVariant> AXOLOTL_VARIANT = register("axolotl/variant", AxolotlVariant.NETWORK_TYPE, AxolotlVariant.CODEC);
    public static final DataComponent<RegistryKey<BinaryTag>> CAT_VARIANT = register("cat/variant", RegistryKey.networkType(opaque(BuiltinRegistries.CAT_VARIANT)), RegistryKey.codec(opaque(BuiltinRegistries.CAT_VARIANT)));
    public static final DataComponent<RegistryKey<BinaryTag>> CAT_SOUND_VARIANT = register("cat/sound_variant", RegistryKey.networkType(opaque(BuiltinRegistries.CAT_SOUND_VARIANT)), RegistryKey.codec(opaque(BuiltinRegistries.CAT_SOUND_VARIANT)));
    public static final DataComponent<DyeColor> CAT_COLLAR = register("cat/collar", DyeColor.NETWORK_TYPE, DyeColor.CODEC);
    public static final DataComponent<DyeColor> SHEEP_COLOR = register("sheep/color", DyeColor.NETWORK_TYPE, DyeColor.CODEC);
    public static final DataComponent<DyeColor> SHULKER_COLOR = register("shulker/color", DyeColor.NETWORK_TYPE, DyeColor.CODEC);

    // There are some components that are serialized to codec as an object containing a single field, for now we just inline them here.
    private static <T> Codec<T> wrapObject(String fieldName, Codec<T> serializer) {
        return StructCodec.struct(fieldName, serializer, t -> t, t -> t);
    }

    private static Registries.Selector<BinaryTag> opaque(RegistryKey<Registry<BinaryTag>> key) {
        return registries -> registries.opaque(key);
    }

    // These components are identifiers in 26.2: a client that cannot parse one drops the connection while decoding
    // the item, so they are checked when they are set rather than when the item reaches a client.
    private static UnaryOperator<String> identifier(String name) {
        return value -> {
            Check.argCondition(!Key.parseable(value),
                    "minecraft:{0} must be a namespaced id such as synaxis:unit/archer (a-z 0-9 . _ - /), got: {1}", name, value);
            return value;
        };
    }

    // A 26.2 client cannot build an item stack template from air and drops the connection while decoding one, so the
    // components holding templates refuse air when they are set rather than when the item reaches a client.
    private static UnaryOperator<ItemStack> notAir(String name) {
        return item -> {
            if (item.isAir())
                throw new IllegalArgumentException("minecraft:" + name + " cannot hold air; remove the component with without() instead");
            return item;
        };
    }

    private static UnaryOperator<List<ItemStack>> noAir(String name) {
        return items -> {
            final List<ItemStack> copy = List.copyOf(items);
            for (int i = 0; i < copy.size(); i++) {
                if (copy.get(i).isAir())
                    throw new IllegalArgumentException("minecraft:" + name + " cannot hold air (index " + i + "); leave the empty slots out instead");
            }
            return copy;
        };
    }

    /**
     * 26.2 sends a container as up to 256 optional item stack templates, where an empty slot is absent. Here an empty
     * slot is air.
     */
    private static NetworkBuffer.Type<List<ItemStack>> containerNetworkType() {
        return ItemStackTemplate.NETWORK_TYPE.optional()
                .transform(item -> item == null ? ItemStack.AIR : item, item -> item.isAir() ? null : item)
                .list(CONTAINER_SIZE);
    }

    /**
     * 26.2 stores a container as its filled slots, each with its index, and an empty slot is left out. Containers
     * stored before that were a plain list of items, which still decodes.
     */
    private static Codec<List<ItemStack>> containerCodec() {
        return ContainerSlot.CODEC.list(CONTAINER_SIZE)
                .transform(DataComponents::containerFromSlots, DataComponents::containerToSlots)
                .orElse(ItemStackTemplate.CODEC.list(CONTAINER_SIZE));
    }

    private static List<ItemStack> containerFromSlots(List<ContainerSlot> slots) {
        int size = 0;
        for (ContainerSlot slot : slots) {
            Check.argCondition(slot.slot() < 0 || slot.slot() >= CONTAINER_SIZE,
                    "container slot {0} is outside 0..255", slot.slot());
            size = Math.max(size, slot.slot() + 1);
        }
        final ItemStack[] items = new ItemStack[size];
        Arrays.fill(items, ItemStack.AIR);
        for (ContainerSlot slot : slots) items[slot.slot()] = slot.item();
        return List.of(items);
    }

    private static List<ContainerSlot> containerToSlots(List<ItemStack> items) {
        Check.argCondition(items.size() > CONTAINER_SIZE, "a container holds at most 256 slots, got {0}", items.size());
        final List<ContainerSlot> slots = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isAir()) slots.add(new ContainerSlot(i, items.get(i)));
        }
        return slots;
    }

    private record ContainerSlot(int slot, ItemStack item) {
        static final StructCodec<ContainerSlot> CODEC = StructCodec.struct(
                "slot", Codec.INT, ContainerSlot::slot,
                "item", ItemStackTemplate.CODEC, ContainerSlot::item,
                ContainerSlot::new);
    }
}
