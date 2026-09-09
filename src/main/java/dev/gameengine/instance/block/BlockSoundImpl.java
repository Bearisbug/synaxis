package dev.gameengine.instance.block;

import dev.gameengine.registry.BuiltinRegistries;
import dev.gameengine.registry.Registry;
import dev.gameengine.registry.RegistryData;
import dev.gameengine.registry.RegistryKey;
import org.jetbrains.annotations.UnknownNullability;

@SuppressWarnings("removal")
record BlockSoundImpl(RegistryData.BlockSoundTypeEntry registry) implements BlockSoundType {
    static final Registry<BlockSoundType> REGISTRY = RegistryData.createStaticRegistry(BuiltinRegistries.BLOCK_SOUND_TYPE,
            (namespace, properties) -> new BlockSoundImpl(RegistryData.blockSoundTypeEntry(namespace, properties)));

    static @UnknownNullability BlockSoundType get(RegistryKey<BlockSoundType> key) {
        return REGISTRY.get(key);
    }
}
