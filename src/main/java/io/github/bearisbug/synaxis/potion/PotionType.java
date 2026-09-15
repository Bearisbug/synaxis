package io.github.bearisbug.synaxis.potion;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.KeyPattern;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.Registry;
import io.github.bearisbug.synaxis.registry.StaticProtocolObject;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

public sealed interface PotionType extends StaticProtocolObject<PotionType>, PotionTypes permits PotionTypeImpl {

    NetworkBuffer.Type<PotionType> NETWORK_TYPE = NetworkBuffer.VAR_INT.transform(PotionType::fromId, PotionType::id);
    Codec<PotionType> CODEC = Codec.KEY.transform(PotionType::fromKey, PotionType::key);

    static Collection<PotionType> values() {
        return PotionTypeImpl.REGISTRY.values();
    }

    static @Nullable PotionType fromKey(@KeyPattern String key) {
        return fromKey(Key.key(key));
    }

    static @Nullable PotionType fromKey(Key key) {
        return PotionTypeImpl.REGISTRY.get(key);
    }

    static @Nullable PotionType fromId(int id) {
        return PotionTypeImpl.REGISTRY.get(id);
    }

    static Registry<PotionType> staticRegistry() {
        return PotionTypeImpl.REGISTRY;
    }
}
