package dev.gameengine.item.component;

import dev.gameengine.codec.Codec;
import dev.gameengine.codec.StructCodec;
import dev.gameengine.entity.damage.DamageType;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.registry.Registries;
import dev.gameengine.registry.RegistryTag;

public record DamageResistant(RegistryTag<DamageType> types) {
    public static final NetworkBuffer.Type<DamageResistant> NETWORK_TYPE = NetworkBufferTemplate.template(
            RegistryTag.networkType(Registries::damageType), DamageResistant::types,
            DamageResistant::new);
    public static final Codec<DamageResistant> CODEC = StructCodec.struct(
            "types", RegistryTag.codec(Registries::damageType), DamageResistant::types,
            DamageResistant::new);
}
