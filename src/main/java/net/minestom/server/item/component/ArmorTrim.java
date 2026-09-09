package net.minestom.server.item.component;

import net.kyori.adventure.nbt.BinaryTag;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.NetworkBufferTemplate;
import net.minestom.server.registry.BuiltinRegistries;
import net.minestom.server.registry.Registries;
import net.minestom.server.registry.RegistryKey;

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
