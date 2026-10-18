package io.github.bearisbug.synaxis.network;

import com.google.gson.JsonPrimitive;
import io.github.bearisbug.synaxis.adventure.EngineDataComponentValue;
import io.github.bearisbug.synaxis.adventure.serializer.nbt.NbtDataComponentValue;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.entity.damage.DamageType;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.DataComponentValue;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.gson.GsonDataComponentValue;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The data components of a {@code show_item} hover event are encoded with the registries of the buffer they are
 * written to, which hold what the owning process registered, not with the vanilla snapshot Adventure's conversion
 * SPI is limited to (upstream Minestom #3400, 268866b01b).
 */
@RegistriesTest
class ComponentHoverItemRegistriesTest {
    private static final String DAMAGE_TYPE = DataComponents.DAMAGE_TYPE.key().asString();

    private static Registries withCustomDamageType() {
        final Registries registries = Registries.vanilla();
        registries.damageType().register("synaxis:hover_damage",
                DamageType.builder().messageId("hover_damage").scaling("never").build());
        return registries;
    }

    private static HoverEvent<HoverEvent.ShowItem> showStone(Key component, DataComponentValue value) {
        return HoverEvent.showItem(HoverEvent.ShowItem.showItem(Material.STONE, 1, Map.of(component, value)));
    }

    private static @Nullable BinaryTag writtenComponent(@Nullable Registries registries,
                                                        HoverEvent<HoverEvent.ShowItem> hoverEvent, String key) {
        final NetworkBuffer buffer = registries != null
                ? NetworkBuffer.resizableBuffer(256, registries) : NetworkBuffer.resizableBuffer(256);
        buffer.write(NetworkBuffer.COMPONENT, Component.text("item").hoverEvent(hoverEvent));
        final CompoundBinaryTag root = (CompoundBinaryTag) buffer.read(NetworkBuffer.NBT);
        return root.getCompound("hover_event").getCompound("components").get(key);
    }

    @Test
    void aJsonValueIsDecodedWithTheRegistriesOfTheBuffer(Registries registries) {
        final Registries custom = withCustomDamageType();
        final HoverEvent<HoverEvent.ShowItem> hoverEvent = showStone(DataComponents.DAMAGE_TYPE.key(),
                GsonDataComponentValue.gsonDataComponentValue(new JsonPrimitive("synaxis:hover_damage")));

        assertEquals(StringBinaryTag.stringBinaryTag("synaxis:hover_damage"),
                writtenComponent(custom, hoverEvent, DAMAGE_TYPE));
        // The registries of a process that did not register it reject the same value
        assertThrows(IllegalArgumentException.class, () -> writtenComponent(registries, hoverEvent, DAMAGE_TYPE));
    }

    @Test
    void anEngineValueIsEncodedWithTheRegistriesOfTheBuffer(Registries registries) {
        final Registries custom = withCustomDamageType();
        final RegistryKey<DamageType> key =
                Objects.requireNonNull(custom.damageType().getKey(Key.key("synaxis:hover_damage")));
        assertNull(registries.damageType().get(Key.key("synaxis:hover_damage")));
        final ItemStack item = ItemStack.of(Material.STONE).with(DataComponents.DAMAGE_TYPE, key);

        assertEquals(StringBinaryTag.stringBinaryTag("synaxis:hover_damage"),
                writtenComponent(custom, item.asHoverEvent(), DAMAGE_TYPE));
    }

    @Test
    void aTypedValueNeedsTheRegistriesOfTheBuffer() {
        final ItemStack item = ItemStack.of(Material.STONE).with(DataComponents.DAMAGE_TYPE, DamageType.GENERIC);

        final IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> writtenComponent(null, item.asHoverEvent(), DAMAGE_TYPE));
        assertTrue(refused.getMessage().contains("needs registries"), refused.getMessage());
    }

    @Test
    void anNbtValueIsWrittenAsItIs() {
        final StringBinaryTag value = StringBinaryTag.stringBinaryTag(DamageType.GENERIC.key().asString());

        assertEquals(value, writtenComponent(null,
                showStone(DataComponents.DAMAGE_TYPE.key(), NbtDataComponentValue.nbtDataComponentValue(value)),
                DAMAGE_TYPE));
    }

    @Test
    void aRemovedValueIsWrittenAsARemoval(Registries registries) {
        assertEquals(CompoundBinaryTag.empty(), writtenComponent(registries,
                showStone(DataComponents.DAMAGE_TYPE.key(), EngineDataComponentValue.removed()), "!" + DAMAGE_TYPE));
    }

    /**
     * Removing a prototype component puts a removal into the patch. Handed over as an engine value of null, it failed
     * to encode on the write thread, which drops the connection of every player the message was sent to.
     */
    @Test
    void aComponentRemovedFromTheItemIsHandedOverAsARemoval(Registries registries) {
        final HoverEvent<HoverEvent.ShowItem> hoverEvent =
                ItemStack.of(Material.APPLE).without(DataComponents.FOOD).asHoverEvent();

        assertInstanceOf(DataComponentValue.Removed.class,
                hoverEvent.value().dataComponents().get(DataComponents.FOOD.key()));
        assertEquals(CompoundBinaryTag.empty(),
                writtenComponent(registries, hoverEvent, "!" + DataComponents.FOOD.key().asString()));
    }

    /**
     * A component registered without a codec has no NBT form, so the hover event leaves it out instead of failing to
     * encode it on the write thread.
     */
    @Test
    void aComponentWithoutCodecIsLeftOut(Registries registries) {
        final String key = DataComponents.ADDITIONAL_TRADE_COST.key().asString();
        final HoverEvent<HoverEvent.ShowItem> hoverEvent =
                ItemStack.of(Material.STONE).with(DataComponents.ADDITIONAL_TRADE_COST, 3).asHoverEvent();

        assertFalse(hoverEvent.value().dataComponents().containsKey(DataComponents.ADDITIONAL_TRADE_COST.key()));
        assertNull(writtenComponent(registries, hoverEvent, key));
    }

    @Test
    void anEngineValueOfNullIsWrittenAsARemoval(Registries registries) {
        assertEquals(CompoundBinaryTag.empty(), writtenComponent(registries,
                showStone(DataComponents.DAMAGE_TYPE.key(), EngineDataComponentValue.dataComponentValue(null)),
                "!" + DAMAGE_TYPE));
    }
}
