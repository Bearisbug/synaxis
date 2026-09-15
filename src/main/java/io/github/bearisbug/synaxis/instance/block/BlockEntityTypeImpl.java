package io.github.bearisbug.synaxis.instance.block;

import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.registry.BuiltinRegistries;
import io.github.bearisbug.synaxis.registry.Registry;
import io.github.bearisbug.synaxis.registry.RegistryData;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.UnknownNullability;

@ApiStatus.Internal
@SuppressWarnings("removal")
record BlockEntityTypeImpl(Key key, int id) implements BlockEntityType {
    static final Registry<BlockEntityType> REGISTRY = RegistryData.createStaticRegistry(
            BuiltinRegistries.BLOCK_ENTITY_TYPE, BlockEntityTypeImpl::new);

    private BlockEntityTypeImpl(String namespace, RegistryData.Properties properties) {
        this(Key.key(namespace), properties.getInt("id"));
    }

    public static @UnknownNullability BlockEntityType get(RegistryKey<BlockEntityType> key) {
        return REGISTRY.get(key);
    }
}
