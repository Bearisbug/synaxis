package io.github.bearisbug.synaxis.world.biome;

import io.github.bearisbug.synaxis.world.attribute.EnvironmentAttributeMap;

import java.util.Objects;

record BiomeImpl(
        boolean hasPrecipitation,
        float temperature,
        TemperatureModifier temperatureModifier,
        float downfall,
        EnvironmentAttributeMap attributes,
        BiomeEffects effects
) implements Biome {

    public BiomeImpl {
        Objects.requireNonNull(temperatureModifier, "temperatureModifier");
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(effects, "effects");
    }

}
