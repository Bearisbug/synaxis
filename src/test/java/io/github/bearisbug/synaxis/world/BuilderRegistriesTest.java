package io.github.bearisbug.synaxis.world;

import com.google.gson.JsonElement;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.world.attribute.EnvironmentAttribute;
import io.github.bearisbug.synaxis.world.attribute.EnvironmentAttribute.Modifier;
import io.github.bearisbug.synaxis.world.attribute.EnvironmentAttributeMap;
import io.github.bearisbug.synaxis.world.biome.Biome;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@RegistriesTest
public class BuilderRegistriesTest {
    @Test
    public void testBiome(Registries registries) {
        Biome existing = registries.biome().get(Biome.CHERRY_GROVE);
        assertNotNull(existing);
        Biome.Builder builder = Biome.builder(existing);
        assertEquals(existing, builder.build());
    }

    @Test
    public void testDimensionType(Registries registries) {
        DimensionType existing = registries.dimensionType().get(DimensionType.THE_NETHER);
        assertNotNull(existing);
        DimensionType.Builder builder = DimensionType.builder(existing);
        assertEquals(existing, builder.build());
    }

    @Test
    public void testEnvironmentAttributeMap(Registries registries) {
        DimensionType existing = registries.dimensionType().get(DimensionType.OVERWORLD);
        assertNotNull(existing);
        EnvironmentAttributeMap.Builder builder = EnvironmentAttributeMap.builder(existing.attributes());
        assertEquals(existing.attributes(), builder.build());
    }

    // No default clock unless given one; the one a builder starts from can be cleared
    @Test
    public void aDimensionWithoutAClockHasNoDefaultClock(Registries registries) {
        assertNull(DimensionType.builder().build().defaultClock());
        DimensionType overworld = registries.dimensionType().get(DimensionType.OVERWORLD);
        assertNotNull(overworld);
        assertNotNull(overworld.defaultClock());
        assertNull(DimensionType.builder(overworld).defaultClock(null).build().defaultClock());
    }

    // The codec of a modifier finds its operator by the instance: each operator of a type has a modifier of its own
    @Test
    public void eachOperatorModifierEncodesToItsOperatorAndBack() {
        assertRoundTrips(EnvironmentAttribute.Type.BOOLEAN, Modifier.BOOLEAN_OPERATORS);
        assertRoundTrips(EnvironmentAttribute.Type.FLOAT, Modifier.FLOAT_OPERATORS);
        assertRoundTrips(EnvironmentAttribute.Type.RGB_COLOR, Modifier.RGB_OPERATORS);
        assertRoundTrips(EnvironmentAttribute.Type.ARGB_COLOR, Modifier.ARGB_OPERATORS);
    }

    private static <T> void assertRoundTrips(EnvironmentAttribute.Type<T> type, Map<Modifier.Operator, Modifier<T, ?>> operators) {
        final Codec<Modifier<T, ?>> codec = type.modifierCodec();
        operators.forEach((operator, modifier) -> {
            final JsonElement encoded = codec.encode(Transcoder.JSON, modifier).orElseThrow();
            assertEquals(Modifier.Operator.CODEC.encode(Transcoder.JSON, operator).orElseThrow(), encoded, operator::name);
            assertSame(modifier, codec.decode(Transcoder.JSON, encoded).orElseThrow(), operator::name);
        });
    }
}
