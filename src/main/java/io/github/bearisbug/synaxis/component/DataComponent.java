package io.github.bearisbug.synaxis.component;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.KeyPattern;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.Decoder;
import io.github.bearisbug.synaxis.codec.Encoder;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.StaticProtocolObject;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

/**
 * A data component type; every instance is registered in {@link DataComponents}.
 *
 * @param <T> The value type of the component
 */
public sealed interface DataComponent<T> extends StaticProtocolObject<DataComponent<T>>, Encoder<T>, Decoder<T> permits DataComponentImpl {

    NetworkBuffer.Type<DataComponent<?>> NETWORK_TYPE = NetworkBuffer.VAR_INT.transform(
            id -> Objects.requireNonNull(fromId(id), () -> "unknown data component id: " + id), DataComponent::id);
    Codec<DataComponent<?>> CODEC = Codec.KEY.transform(
            key -> Objects.requireNonNull(fromKey(key), () -> "unknown data component: " + key.asString()), DataComponent::key);

    NetworkBuffer.Type<DataComponentMap> MAP_NETWORK_TYPE = new DataComponentMapImpl.NetworkTypeImpl(false, true);
    Codec<DataComponentMap> MAP_NBT_TYPE = new DataComponentMapImpl.CodecImpl(false);

    NetworkBuffer.Type<DataComponentMap> PATCH_NETWORK_TYPE = new DataComponentMapImpl.NetworkTypeImpl(true, true);
    NetworkBuffer.Type<DataComponentMap> UNTRUSTED_PATCH_NETWORK_TYPE = new DataComponentMapImpl.NetworkTypeImpl(true, false);
    Codec<DataComponentMap> PATCH_CODEC = new DataComponentMapImpl.CodecImpl(true);

    /**
     * Represents any type which can hold data components. Represents a finalized view of a component, that is to say
     * an implementation may represent a patch on top of another Holder, however the return values of this type
     * will always represent the merged view.
     */
    interface Holder {
        default boolean has(DataComponent<?> component) {
            return get(component) != null;
        }

        <T> @Nullable T get(DataComponent<T> component);

        default <T> T get(DataComponent<T> component, T defaultValue) {
            final T value = get(component);
            return value != null ? value : defaultValue;
        }
    }

    record Value(DataComponent<?> component, @Nullable Object value) {
    }

    /**
     * Whether the component has a network type. Without one it is skipped, without an error, when
     * a component map is written to the network.
     *
     * @return whether {@link #networkType()} is not null
     */
    boolean isSynced();

    /**
     * Whether the component has a codec. Without one it is skipped, without an error, when a
     * component map is encoded with a codec, when an item is hashed and when an item is shown in a
     * hover event.
     *
     * @return whether {@link #codec()} is not null
     */
    boolean isSerialized();

    /**
     * The network type of the component.
     *
     * @return the network type, null when the component is not synced (see {@link #isSynced()})
     */
    @Nullable NetworkBuffer.Type<T> networkType();

    /**
     * The codec of the component.
     *
     * @return the codec, null when the component is not serialized (see {@link #isSerialized()})
     */
    @Nullable Codec<T> codec();

    T read(NetworkBuffer reader);
    void write(NetworkBuffer writer, T value);

    /**
     * Freezes the given value if possible. For example, collections should be frozen.
     * <br>
     * Note: Only {@link T} itself is required to be frozen, the objects inside {@link T} should be immutable.
     *
     * @param value the value to freeze
     * @return the frozen value, or the original value if it could not be frozen
     */
    T freeze(T value);

    static @Nullable DataComponent<?> fromKey(@KeyPattern String key) {
        try {
            return fromKey(Key.key(key));
        } catch (InvalidKeyException _) {
            return null;
        }
    }

    static @Nullable DataComponent<?> fromKey(Key key) {
        return DataComponentImpl.NAMESPACES.get(key);
    }

    static @Nullable DataComponent<?> fromId(int id) {
        return DataComponentImpl.IDS.get(id);
    }

    static Collection<DataComponent<?>> values() {
        return Collections.unmodifiableCollection(DataComponentImpl.NAMESPACES.values());
    }
}
