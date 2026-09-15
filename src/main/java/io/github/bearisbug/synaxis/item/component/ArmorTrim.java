package io.github.bearisbug.synaxis.item.component;

import net.kyori.adventure.nbt.BinaryTag;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.registry.BuiltinRegistries;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryKey;

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
