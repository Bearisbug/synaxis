package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.entity.damage.DamageType;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryTag;

public record DamageResistant(RegistryTag<DamageType> types) {
    public static final NetworkBuffer.Type<DamageResistant> NETWORK_TYPE = NetworkBufferTemplate.template(
            RegistryTag.networkType(Registries::damageType), DamageResistant::types,
            DamageResistant::new);
    public static final Codec<DamageResistant> CODEC = StructCodec.struct(
            "types", RegistryTag.codec(Registries::damageType), DamageResistant::types,
            DamageResistant::new);
}
