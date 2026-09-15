package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.registry.BuiltinRegistries;
import io.github.bearisbug.synaxis.registry.Registry;
import io.github.bearisbug.synaxis.registry.RegistryData;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import org.jetbrains.annotations.UnknownNullability;

@SuppressWarnings("removal")
public record VillagerProfessionImpl(RegistryData.VillagerProfessionEntry registry) implements VillagerProfession {
    static final Registry<VillagerProfession> REGISTRY = RegistryData.createStaticRegistry(BuiltinRegistries.VILLAGER_PROFESSION,
            (namespace, properties) -> new VillagerProfessionImpl(RegistryData.villagerProfession(namespace, properties)));

    static @UnknownNullability VillagerProfession get(RegistryKey<VillagerProfession> key) {
        return REGISTRY.get(key);
    }

    @Override
    public String toString() {
        return name();
    }
}
