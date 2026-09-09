package net.minestom.server.world;

import net.kyori.adventure.nbt.BinaryTag;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.registry.RegistryTag;
import net.minestom.server.utils.IntProvider;
import net.minestom.server.world.attribute.EnvironmentAttributeMap;
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
