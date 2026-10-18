package io.github.bearisbug.synaxis.world.attribute;

import net.kyori.adventure.util.RGBLike;
import io.github.bearisbug.synaxis.color.AlphaColor;
import io.github.bearisbug.synaxis.color.Color;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An attribute map is encoded into the registry data of every configuration phase. An entry its codec cannot write
 * failed there, for every player, so it is refused when the map is built.
 */
public class EnvironmentAttributeMapTest {

    /** The modifier compiles against an RGB attribute, but only MULTIPLY_RGB is the multiply of that type. */
    @Test
    public void aModifierTheTypeDoesNotTakeIsRejected() {
        final EnvironmentAttributeMap.Builder builder = EnvironmentAttributeMap.builder()
                .modify(EnvironmentAttribute.SKY_COLOR, EnvironmentAttribute.Modifier.Color.MULTIPLY_ARGB,
                        new AlphaColor(0x80FF0000));

        final String message = assertThrows(IllegalArgumentException.class, builder::build).getMessage();
        assertTrue(message.contains("minecraft:visual/sky_color") && message.contains("MULTIPLY"), message);
    }

    @Test
    public void aNullArgumentIsRejected() {
        final @Nullable RGBLike absent = null;
        final EnvironmentAttributeMap.Builder builder = EnvironmentAttributeMap.builder()
                .set(EnvironmentAttribute.FOG_COLOR, absent);

        final String message = assertThrows(NullPointerException.class, builder::build).getMessage();
        assertTrue(message.contains("minecraft:visual/fog_color"), message);
    }

    @Test
    public void overridesAndTheModifiersOfTheTypeAreTaken() {
        assertDoesNotThrow(() -> EnvironmentAttributeMap.builder()
                .set(EnvironmentAttribute.FOG_COLOR, new Color(0x102030))
                .modify(EnvironmentAttribute.SKY_COLOR, EnvironmentAttribute.Modifier.Color.MULTIPLY_RGB, new Color(0x808080))
                .modify(EnvironmentAttribute.WATER_FOG_COLOR, EnvironmentAttribute.Modifier.Color.ADD, new Color(0x010101))
                .build());
    }
}
