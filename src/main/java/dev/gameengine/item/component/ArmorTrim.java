package dev.gameengine.item.component;

import net.kyori.adventure.nbt.BinaryTag;
import dev.gameengine.codec.Codec;
import dev.gameengine.codec.StructCodec;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.registry.BuiltinRegistries;
import dev.gameengine.registry.Registries;
import dev.gameengine.registry.RegistryKey;

public record ArmorTrim(
        RegistryKey<BinaryTag> material,
        RegistryKey<BinaryTag> pattern
) {
    private static final Registries.Selector<BinaryTag> MATERIAL = registries -> registries.opaque(BuiltinRegistries.TRIM_MATERIAL);
    private static final Registries.Selector<BinaryTag> PATTERN = registries -> registries.opaque(BuiltinRegistries.TRIM_PATTERN);

    public static final NetworkBuffer.Type<ArmorTrim> NETWORK_TYPE = NetworkBufferTemplate.template(
            RegistryKey.holderNetworkType(MATERIAL), ArmorTrim::material,
            RegistryKey.holderNetworkType(PATTERN), ArmorTrim::pattern,
            ArmorTrim::new);
    public static final Codec<ArmorTrim> CODEC = StructCodec.struct(
            "material", RegistryKey.codec(MATERIAL), ArmorTrim::material,
            "pattern", RegistryKey.codec(PATTERN), ArmorTrim::pattern,
            ArmorTrim::new);

}
