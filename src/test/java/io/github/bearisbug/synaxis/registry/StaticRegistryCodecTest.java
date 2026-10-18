package io.github.bearisbug.synaxis.registry;

import io.github.bearisbug.synaxis.FeatureFlag;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.component.DataComponent;
import io.github.bearisbug.synaxis.component.DataComponentMap;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.EquipmentSlot;
import io.github.bearisbug.synaxis.entity.EquipmentSlotGroup;
import io.github.bearisbug.synaxis.entity.VillagerProfession;
import io.github.bearisbug.synaxis.entity.attribute.Attribute;
import io.github.bearisbug.synaxis.gameevent.GameEvent;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockEntityType;
import io.github.bearisbug.synaxis.instance.block.BlockSoundType;
import io.github.bearisbug.synaxis.instance.fluid.Fluid;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.particle.Particle;
import io.github.bearisbug.synaxis.potion.PotionEffect;
import io.github.bearisbug.synaxis.potion.PotionType;
import io.github.bearisbug.synaxis.sound.SoundEvent;
import io.github.bearisbug.synaxis.worldevent.WorldEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A key or an id that names nothing in a static registry is a decoding failure, never a value of null.
 */
public class StaticRegistryCodecTest {

    private static Stream<Arguments> keyCodecs() {
        return Stream.of(
                Arguments.of("potion effect", PotionEffect.CODEC),
                Arguments.of("potion type", PotionType.CODEC),
                Arguments.of("data component", DataComponent.CODEC),
                Arguments.of("material", Material.CODEC),
                Arguments.of("entity type", EntityType.CODEC),
                Arguments.of("attribute", Attribute.CODEC),
                Arguments.of("block entity type", BlockEntityType.CODEC),
                Arguments.of("villager profession", VillagerProfession.NBT_TYPE));
    }

    @ParameterizedTest
    @MethodSource("keyCodecs")
    void anUnknownKeyIsAnError(String registry, Codec<?> codec) {
        assertErrorContaining("unknown " + registry + ": minecraft:nope", codec.decode(Transcoder.JAVA, "minecraft:nope"));
    }

    private static Stream<Arguments> idTypes() {
        return Stream.of(
                Arguments.of("data component", DataComponent.NETWORK_TYPE),
                Arguments.of("world event", WorldEvent.NETWORK_TYPE),
                Arguments.of("equipment slot", EquipmentSlot.NETWORK_TYPE));
    }

    @ParameterizedTest
    @MethodSource("idTypes")
    void anUnknownIdFailsTheRead(String registry, NetworkBuffer.Type<?> type) {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer();
        buffer.write(NetworkBuffer.VAR_INT, 99_999);

        final NullPointerException error = assertThrows(NullPointerException.class, () -> buffer.read(type));
        assertEquals("unknown " + registry + " id: 99999", error.getMessage());
    }

    private static Stream<NetworkBuffer.Type<?>> staticRegistryIdTypes() {
        return Stream.of(PotionEffect.NETWORK_TYPE, PotionType.NETWORK_TYPE, GameEvent.NETWORK_TYPE,
                Material.NETWORK_TYPE, EntityType.NETWORK_TYPE, Attribute.NETWORK_TYPE, BlockEntityType.NETWORK_TYPE,
                VillagerProfession.NETWORK_TYPE);
    }

    // Their static registry throws for an id it does not hold, so these reads fail without a lookup check
    @ParameterizedTest
    @MethodSource("staticRegistryIdTypes")
    void anUnknownIdOfAStaticRegistryFailsTheRead(NetworkBuffer.Type<?> type) {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer();
        buffer.write(NetworkBuffer.VAR_INT, 99_999);

        assertThrows(IndexOutOfBoundsException.class, () -> buffer.read(type));
    }

    private static Stream<Arguments> stringLookups() {
        return Stream.of(
                Arguments.of("FeatureFlag", (Function<String, Object>) FeatureFlag::fromKey),
                Arguments.of("GameEvent", (Function<String, Object>) GameEvent::fromKey),
                Arguments.of("DataComponent", (Function<String, Object>) DataComponent::fromKey),
                Arguments.of("VillagerProfession", (Function<String, Object>) VillagerProfession::fromKey),
                Arguments.of("PotionEffect", (Function<String, Object>) PotionEffect::fromKey),
                Arguments.of("PotionType", (Function<String, Object>) PotionType::fromKey),
                Arguments.of("Attribute", (Function<String, Object>) Attribute::fromKey),
                Arguments.of("BlockSoundType", (Function<String, Object>) BlockSoundType::fromKey),
                Arguments.of("BlockEntityType", (Function<String, Object>) BlockEntityType::fromKey),
                Arguments.of("Material", (Function<String, Object>) Material::fromKey),
                Arguments.of("Fluid", (Function<String, Object>) Fluid::fromKey),
                Arguments.of("SoundEvent", (Function<String, Object>) SoundEvent::fromKey),
                Arguments.of("Block", (Function<String, Object>) Block::fromKey),
                Arguments.of("EntityType", (Function<String, Object>) EntityType::fromKey),
                Arguments.of("Particle", (Function<String, Object>) Particle::fromKey));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stringLookups")
    void aStringThatIsNotAKeyFindsNothing(String type, Function<String, Object> fromKey) {
        assertNull(fromKey.apply("Bad Key"), type);
    }

    @Test
    void aComponentNameThatIsNotAKeyIsAnError() {
        assertError("unknown data component: Bad Key",
                DataComponent.PATCH_CODEC.decode(Transcoder.JAVA, Map.of("Bad Key", 1)));
    }

    @Test
    void anUnknownKeyNestedInAComponentIsAnError() {
        // Naming the components through their constants registers them before the patch codec looks them up
        final Result<DataComponentMap> potion = DataComponent.PATCH_CODEC.decode(Transcoder.JAVA, Map.of(
                DataComponents.POTION_CONTENTS.name(), Map.of("custom_effects", List.of(Map.of("id", "minecraft:nope")))));
        assertErrorContaining("unknown potion effect: minecraft:nope", potion);

        final Result<DataComponentMap> tooltip = DataComponent.PATCH_CODEC.decode(Transcoder.JAVA, Map.of(
                DataComponents.TOOLTIP_DISPLAY.name(), Map.of("hidden_components", List.of("minecraft:nope"))));
        assertErrorContaining("unknown data component: minecraft:nope", tooltip);

        final Result<?> modifiers = DataComponents.ATTRIBUTE_MODIFIERS.decode(Transcoder.JAVA, List.of(Map.of(
                "type", "minecraft:attack_damage", "id", "synaxis:test", "amount", 1.0,
                "operation", "add_value", "slot", "everywhere")));
        assertErrorContaining("unknown equipment slot group: everywhere", modifiers);
    }

    @Test
    void anUnknownEquipmentSlotNamesTheValidOnes() {
        final Result<EquipmentSlot> slot = EquipmentSlot.CODEC.decode(Transcoder.JAVA, "mainhan");
        assertErrorContaining("unknown equipment slot: mainhan, expected one of", slot);
        assertErrorContaining("mainhand", slot);

        final Result<EquipmentSlotGroup> group = EquipmentSlotGroup.CODEC.decode(Transcoder.JAVA, "everywhere");
        assertErrorContaining("unknown equipment slot group: everywhere, expected one of", group);
        assertErrorContaining("armor", group);
    }

    private static void assertErrorContaining(String expected, Result<?> result) {
        final String message = assertInstanceOf(Result.Error.class, result).message();
        assertTrue(message.contains(expected), message);
    }
}
