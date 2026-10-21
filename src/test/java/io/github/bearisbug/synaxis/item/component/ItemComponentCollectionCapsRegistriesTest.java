package io.github.bearisbug.synaxis.item.component;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.util.RGBLike;
import io.github.bearisbug.synaxis.color.Color;
import io.github.bearisbug.synaxis.color.DyeColor;
import io.github.bearisbug.synaxis.component.DataComponent;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.EquipmentSlotGroup;
import io.github.bearisbug.synaxis.entity.attribute.Attribute;
import io.github.bearisbug.synaxis.entity.attribute.AttributeModifier;
import io.github.bearisbug.synaxis.entity.attribute.AttributeOperation;
import io.github.bearisbug.synaxis.instance.block.predicate.BlockPredicate;
import io.github.bearisbug.synaxis.item.ItemAnimation;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.potion.CustomPotionEffect;
import io.github.bearisbug.synaxis.potion.PotionEffect;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.registry.RegistryTag;
import io.github.bearisbug.synaxis.sound.SoundEvent;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The item component lists that a 26.2 client reads at any length are written at any length, and
 * still read with the cap they had: the server reads the same types from a client's creative
 * inventory uploads.
 */
@RegistriesTest
public class ItemComponentCollectionCapsRegistriesTest {

    private static final CustomPotionEffect SPEED = new CustomPotionEffect(PotionEffect.SPEED, 0, 20, false, true, true);

    /** Writes {@code value}, which holds one element more than reading accepts, and reads it back. */
    private static <T> void assertWrittenPastTheReadCap(DataComponent<T> component, T value, Registries registries) {
        final byte[] bytes = assertDoesNotThrow(() -> NetworkBuffer.makeArray(buffer -> component.write(buffer, value), registries));
        assertThrows(IllegalArgumentException.class,
                () -> component.read(NetworkBuffer.wrap(bytes, 0, bytes.length, registries)));
    }

    @Test
    void blockPredicates(Registries registries) {
        assertWrittenPastTheReadCap(DataComponents.CAN_PLACE_ON,
                new BlockPredicates(Collections.nCopies(32768, BlockPredicate.ALL)), registries);
    }

    @Test
    void attributeModifiers(Registries registries) {
        final var modifier = new AttributeList.Modifier(Attribute.ARMOR,
                new AttributeModifier("synaxis:armor", 1, AttributeOperation.ADD_VALUE), EquipmentSlotGroup.ANY);
        assertWrittenPastTheReadCap(DataComponents.ATTRIBUTE_MODIFIERS,
                new AttributeList(Collections.nCopies(32768, modifier)), registries);
    }

    @Test
    void customModelData(Registries registries) {
        assertWrittenPastTheReadCap(DataComponents.CUSTOM_MODEL_DATA,
                new CustomModelData(Collections.nCopies(257, 1f), List.of(), List.of(), List.of()), registries);
        assertWrittenPastTheReadCap(DataComponents.CUSTOM_MODEL_DATA,
                new CustomModelData(List.of(), Collections.nCopies(257, true), List.of(), List.of()), registries);
        assertWrittenPastTheReadCap(DataComponents.CUSTOM_MODEL_DATA,
                new CustomModelData(List.of(), List.of(), Collections.nCopies(257, "x"), List.of()), registries);
        assertWrittenPastTheReadCap(DataComponents.CUSTOM_MODEL_DATA,
                new CustomModelData(List.of(), List.of(), List.of(), Collections.nCopies(257, new Color(1, 2, 3))), registries);
    }

    @Test
    void consumeEffects(Registries registries) {
        assertWrittenPastTheReadCap(DataComponents.CONSUMABLE, consumable(Collections.nCopies(32768,
                ConsumeEffect.ClearAllEffects.INSTANCE)), registries);
        assertWrittenPastTheReadCap(DataComponents.CONSUMABLE, consumable(List.of(
                new ConsumeEffect.ApplyEffects(Collections.nCopies(257, SPEED), 1f))), registries);
        assertWrittenPastTheReadCap(DataComponents.DEATH_PROTECTION,
                new DeathProtection(Collections.nCopies(257, ConsumeEffect.ClearAllEffects.INSTANCE)), registries);
    }

    private static Consumable consumable(List<ConsumeEffect> effects) {
        return new Consumable(Consumable.DEFAULT_CONSUME_SECONDS, ItemAnimation.EAT, SoundEvent.ENTITY_GENERIC_EAT, true, effects);
    }

    @Test
    void toolRules(Registries registries) {
        final var rule = new Tool.Rule(RegistryTag.empty(), 1f, true);
        assertWrittenPastTheReadCap(DataComponents.TOOL, new Tool(Collections.nCopies(32768, rule), 1f, 1, true), registries);
    }

    @Test
    void damageReductions(Registries registries) {
        assertWrittenPastTheReadCap(DataComponents.BLOCKS_ATTACKS, new BlocksAttacks(0f, 1f,
                Collections.nCopies(32768, BlocksAttacks.DamageReduction.DEFAULT), BlocksAttacks.ItemDamageFunction.DEFAULT,
                null, null, null), registries);
    }

    @Test
    void bundleContents(Registries registries) {
        assertWrittenPastTheReadCap(DataComponents.BUNDLE_CONTENTS, Collections.nCopies(32768, ItemStack.of(Material.ARROW)), registries);
    }

    @Test
    void potionAndStewEffects(Registries registries) {
        assertWrittenPastTheReadCap(DataComponents.POTION_CONTENTS, new PotionContents(Collections.nCopies(32768, SPEED)), registries);
        assertWrittenPastTheReadCap(DataComponents.SUSPICIOUS_STEW_EFFECTS, new SuspiciousStewEffects(
                Collections.nCopies(32768, new SuspiciousStewEffects.Effect(PotionEffect.SPEED))), registries);
    }

    @Test
    void writtenBookPages(Registries registries) {
        assertWrittenPastTheReadCap(DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent("title", "author", Collections.nCopies(101, Component.text("page"))), registries);
    }

    @Test
    void fireworkColors(Registries registries) {
        final List<RGBLike> colors = Collections.nCopies(32768, new Color(1, 2, 3));
        assertWrittenPastTheReadCap(DataComponents.FIREWORK_EXPLOSION,
                new FireworkExplosion(FireworkExplosion.Shape.STAR, colors, List.of(), false, false), registries);
        assertWrittenPastTheReadCap(DataComponents.FIREWORK_EXPLOSION,
                new FireworkExplosion(FireworkExplosion.Shape.STAR, List.of(), colors, false, false), registries);
    }

    @Test
    void bannerLayers(Registries registries) {
        final var layer = new BannerPatterns.Layer(RegistryKey.unsafeOf("minecraft:base"), DyeColor.RED);
        assertWrittenPastTheReadCap(DataComponents.BANNER_PATTERNS, new BannerPatterns(Collections.nCopies(1025, layer)), registries);
    }

    @Test
    void bees(Registries registries) {
        final var bee = new Bee(new TypedCustomData<>(EntityType.BEE, CompoundBinaryTag.empty()), 0, 0);
        assertWrittenPastTheReadCap(DataComponents.BEES, Collections.nCopies(32768, bee), registries);
    }
}
