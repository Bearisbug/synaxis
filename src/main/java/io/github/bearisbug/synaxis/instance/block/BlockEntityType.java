package io.github.bearisbug.synaxis.instance.block;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.KeyPattern;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.Registry;
import io.github.bearisbug.synaxis.registry.StaticProtocolObject;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

public sealed interface BlockEntityType extends StaticProtocolObject<BlockEntityType>, BlockEntityTypes permits BlockEntityTypeImpl {
    NetworkBuffer.Type<BlockEntityType> NETWORK_TYPE = NetworkBuffer.VAR_INT.transform(BlockEntityType::fromId, BlockEntityType::id);
    Codec<BlockEntityType> CODEC = Codec.KEY.transform(BlockEntityType::fromKey, BlockEntityType::key);

    static Collection<BlockEntityType> values() {
        return BlockEntityTypeImpl.REGISTRY.values();
    }

    static @Nullable BlockEntityType fromKey(@KeyPattern String key) {
        return fromKey(Key.key(key));
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
