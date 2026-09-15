package io.github.bearisbug.synaxis.registry;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import org.jetbrains.annotations.ApiStatus;

/**
 * Represents a reference to a {@link Registry} entry.
 *
 * @param <T> the type of the registry entry
 */
@ApiStatus.NonExtendable
public non-sealed interface RegistryKey<T> extends Holder<T>, Keyed {

    static <T> NetworkBuffer.Type<RegistryKey<T>> networkType(Registries.Selector<T> selector) {
        return new RegistryNetworkTypes.RegistryKeyImpl<>(selector, false);
    }

    static <T> Codec<RegistryKey<T>> codec(Registries.Selector<T> selector) {
        return new RegistryCodecs.RegistryKeyImpl<>(selector);
    }

    /**
     * <p>The network type of a registry reference framed as a vanilla holder: the id is written offset by one, and
     * zero marks an inline value.</p>
     *
     * <p>Registries without a server-side type cannot decode an inline value, so reading a zero fails the packet.
     * Nothing in the engine writes one.</p>
     */
    static <T> NetworkBuffer.Type<RegistryKey<T>> holderNetworkType(Registries.Selector<T> selector) {
        return new RegistryNetworkTypes.RegistryKeyImpl<>(selector, true);
    }

    static <T> NetworkBuffer.Type<RegistryKey<T>> uncheckedNetworkType() {
        return NetworkBuffer.KEY.transform(RegistryKeyImpl::new, RegistryKey::key);
    }

    static <T> Codec<RegistryKey<T>> uncheckedCodec() {
        return Codec.KEY.transform(RegistryKeyImpl::new, RegistryKey::key);
    }

    /**
     * Creates a new {@link RegistryKey} from the given raw string. Should not be used externally.
     * Registry keys are returned from {@link DynamicRegistry#register(Key, Object)}.
     */
    @ApiStatus.Internal
    static <T> RegistryKey<T> unsafeOf(String key) {
        return unsafeOf(Key.key(key));
    }

    /**
     * Creates a new {@link RegistryKey} from the given raw string. Should not be used externally.
     * Registry keys are returned from {@link DynamicRegistry#register(Key, Object)}.
     */
    @ApiStatus.Internal
    static <T> RegistryKey<T> unsafeOf(Key key) {
        return new RegistryKeyImpl<>(key);
    }

    default String name() {
        return key().asString();
    }

}
