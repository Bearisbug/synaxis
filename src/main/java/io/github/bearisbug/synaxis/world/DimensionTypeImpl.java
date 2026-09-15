package io.github.bearisbug.synaxis.world;

import net.kyori.adventure.nbt.BinaryTag;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.registry.RegistryTag;
import io.github.bearisbug.synaxis.world.attribute.EnvironmentAttributeMap;
import org.jetbrains.annotations.Nullable;

record DimensionTypeImpl(
        boolean hasFixedTime,
        boolean hasSkylight,
        boolean hasCeiling,
        boolean hasEnderDragonFight,
        double coordinateScale,
        int minY,
        int height,
        int logicalHeight,
        String infiniburn,
        float ambientLight,
        IntProvider monsterSpawnLightLevel,
        int monsterSpawnBlockLightLimit,
        Skybox skybox,
        CardinalLight cardinalLight,
        EnvironmentAttributeMap attributes,
        RegistryTag<BinaryTag> timelines,
        @Nullable RegistryKey<BinaryTag> defaultClock
) implements DimensionType {
}
