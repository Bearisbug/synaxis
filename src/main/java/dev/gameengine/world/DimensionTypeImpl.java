package dev.gameengine.world;

import net.kyori.adventure.nbt.BinaryTag;
import dev.gameengine.registry.RegistryKey;
import dev.gameengine.registry.RegistryTag;
import dev.gameengine.utils.IntProvider;
import dev.gameengine.world.attribute.EnvironmentAttributeMap;
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
