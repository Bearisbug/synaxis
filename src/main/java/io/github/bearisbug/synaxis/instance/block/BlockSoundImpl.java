package io.github.bearisbug.synaxis.instance.block;

import io.github.bearisbug.synaxis.registry.BuiltinRegistries;
import io.github.bearisbug.synaxis.registry.Registry;
import io.github.bearisbug.synaxis.registry.RegistryData;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import org.jetbrains.annotations.UnknownNullability;

@SuppressWarnings("removal")
record BlockSoundImpl(RegistryData.BlockSoundTypeEntry registry) implements BlockSoundType {
    static final Registry<BlockSoundType> REGISTRY = RegistryData.createStaticRegistry(BuiltinRegistries.BLOCK_SOUND_TYPE,
            (namespace, properties) -> new BlockSoundImpl(RegistryData.blockSoundTypeEntry(namespace, properties)));

    static @UnknownNullability BlockSoundType get(RegistryKey<BlockSoundType> key) {
        return REGISTRY.get(key);
    }
}
