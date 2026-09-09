package dev.gameengine.registry;

import net.kyori.adventure.nbt.BinaryTag;
import dev.gameengine.codec.Codec;
import dev.gameengine.component.DataComponents;
import dev.gameengine.entity.damage.DamageType;
import dev.gameengine.instance.block.predicate.DataComponentPredicate;
import dev.gameengine.message.ChatType;
import dev.gameengine.world.DimensionType;
import dev.gameengine.world.biome.Biome;

import java.util.HashMap;
import java.util.Map;

final class VanillaRegistries implements Registries {
    private final DynamicRegistry<Codec<? extends DataComponentPredicate>> componentPredicateTypes;

    private final Map<RegistryKey<Registry<BinaryTag>>, DynamicRegistry<BinaryTag>> opaque;

    private final DynamicRegistry<ChatType> chatType;
    private final DynamicRegistry<DimensionType> dimensionType;
    private final DynamicRegistry<Biome> biome;
    private final DynamicRegistry<DamageType> damageType;

    @SuppressWarnings("removal")
    VanillaRegistries() {
        // The order of initialization here is relevant, we must load the component predicates before the vanilla data is loaded.
        var _ = DataComponents.ITEM_NAME;

        this.componentPredicateTypes = DataComponentPredicate.createDefaultRegistry();

        // Opaque registries carry no cross references, so they can be loaded first and satisfy the
        // dimension type references to the timeline and world clock registries.
        final Map<RegistryKey<Registry<BinaryTag>>, DynamicRegistry<BinaryTag>> opaque = new HashMap<>();
        for (RegistryKey<Registry<BinaryTag>> key : RegistriesImpl.OPAQUE_KEYS)
            opaque.put(key, DynamicRegistry.createOpaque(key));
        this.opaque = Map.copyOf(opaque);

        this.chatType = ChatType.createDefaultRegistry();
        this.biome = Biome.createDefaultRegistry();
        this.damageType = DamageType.createDefaultRegistry();
        this.dimensionType = DimensionType.createDefaultRegistry(this);

        // Quite a hack because materials are a static registry, and can be loaded before but are cyclic on components.
        // So we break the loop and bind them here
        for (var entry: material().values()) {
            entry.registry().bindComponents(this);
        }
    }

    @Override
    public DynamicRegistry<ChatType> chatType() {
        return chatType;
    }

    @Override
    public DynamicRegistry<DimensionType> dimensionType() {
        return dimensionType;
    }

    @Override
    public DynamicRegistry<Biome> biome() {
        return biome;
    }

    @Override
    public DynamicRegistry<DamageType> damageType() {
        return damageType;
    }

    @Override
    public DynamicRegistry<BinaryTag> opaque(RegistryKey<Registry<BinaryTag>> key) {
        final DynamicRegistry<BinaryTag> registry = opaque.get(key);
        if (registry == null) throw new IllegalArgumentException("Not an opaque registry: " + key.name());
        return registry;
    }

    @Override
    public DynamicRegistry<Codec<? extends DataComponentPredicate>> componentPredicateTypes() {
        return componentPredicateTypes;
    }
}
