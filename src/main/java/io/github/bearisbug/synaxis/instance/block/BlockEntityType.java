package io.github.bearisbug.synaxis.instance.block;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.KeyPattern;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.Registry;
import io.github.bearisbug.synaxis.registry.StaticProtocolObject;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Objects;

public sealed interface BlockEntityType extends StaticProtocolObject<BlockEntityType>, BlockEntityTypes permits BlockEntityTypeImpl {
    NetworkBuffer.Type<BlockEntityType> NETWORK_TYPE = NetworkBuffer.VAR_INT.transform(
            id -> Objects.requireNonNull(BlockEntityType.fromId(id), () -> "unknown block entity type id: " + id), BlockEntityType::id);
    Codec<BlockEntityType> CODEC = Codec.KEY.transform(
            key -> Objects.requireNonNull(fromKey(key), () -> "unknown block entity type: " + key.asString()), BlockEntityType::key);

    static Collection<BlockEntityType> values() {
        return BlockEntityTypeImpl.REGISTRY.values();
    }

    static @Nullable BlockEntityType fromKey(@KeyPattern String key) {
        try {
            return fromKey(Key.key(key));
        } catch (InvalidKeyException _) {
            return null;
        }
    }

    static @Nullable BlockEntityType fromKey(Key key) {
        return BlockEntityTypeImpl.REGISTRY.get(key);
    }

    static @Nullable BlockEntityType fromId(int id) {
        return BlockEntityTypeImpl.REGISTRY.get(id);
    }

    static Registry<BlockEntityType> staticRegistry() {
        return BlockEntityTypeImpl.REGISTRY;
    }

}
