package io.github.bearisbug.synaxis.entity.attribute;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.KeyPattern;
import net.kyori.adventure.translation.Translatable;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.RegistryData;
import io.github.bearisbug.synaxis.registry.StaticProtocolObject;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Objects;

public sealed interface Attribute extends StaticProtocolObject<Attribute>, Attributes,
        Translatable permits AttributeImpl {
    NetworkBuffer.Type<Attribute> NETWORK_TYPE = NetworkBuffer.VAR_INT.transform(
            id -> Objects.requireNonNull(Attribute.fromId(id), () -> "unknown attribute id: " + id), Attribute::id);
    Codec<Attribute> CODEC = Codec.KEY.transform(
            key -> Objects.requireNonNull(fromKey(key), () -> "unknown attribute: " + key.asString()), Attribute::key);

    /**
     * Returns the legacy registry data backing this attribute.
     *
     * @return the legacy registry data
     * @deprecated use the direct accessors on {@link Attribute}
     */
    @Deprecated(forRemoval = true)
    @SuppressWarnings("removal")
    @Override
    @Contract(pure = true)
    RegistryData.AttributeEntry registry();

    @Override
    default Key key() {
        return registry().key();
    }

    @Override
    default int id() {
        return registry().id();
    }

    /**
     * Returns this attribute's default value.
     *
     * @return the default value
     */
    @Contract(pure = true)
    default double defaultValue() {
        return registry().defaultValue();
    }

    /**
     * Returns the minimum accepted value for this attribute.
     *
     * @return the minimum value
     */
    @Contract(pure = true)
    default double minValue() {
        return registry().minValue();
    }

    /**
     * Returns the maximum accepted value for this attribute.
     *
     * @return the maximum value
     */
    @Contract(pure = true)
    default double maxValue() {
        return registry().maxValue();
    }

    /**
     * Returns whether this attribute is synchronized with clients.
     *
     * @return {@code true} if this attribute is synchronized
     */
    @Contract(pure = true)
    default boolean synced() {
        return registry().clientSync();
    }

    /**
     * @deprecated use {@link #synced()}
     */
    @Deprecated(forRemoval = true)
    @Contract(pure = true)
    default boolean isSynced() {
        return synced();
    }

    @Override
    default String translationKey() {
        return registry().translationKey();
    }

    static Collection<Attribute> values() {
        return AttributeImpl.REGISTRY.values();
    }

    static @Nullable Attribute fromKey(@KeyPattern String key) {
        try {
            return fromKey(Key.key(key));
        } catch (InvalidKeyException _) {
            return null;
        }
    }

    static @Nullable Attribute fromKey(Key key) {
        return AttributeImpl.REGISTRY.get(key);
    }

    static @Nullable Attribute fromId(int id) {
        return AttributeImpl.REGISTRY.get(id);
    }

}
