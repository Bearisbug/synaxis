package net.minestom.server.registry;

import net.minestom.server.utils.Either;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <p>Represents either a reference to a registry entry {@link RegistryKey} or a direct registry value.</p>
 *
 * <p>Whether registry values implement this type depends on client support for direct values.</p>
 *
 * @param <T> the type of the registry entry
 */
public sealed interface Holder<T> permits RegistryKey, Holder.Direct {

    @ApiStatus.NonExtendable
    non-sealed interface Direct<T> extends Holder<T> {
    }



    default boolean isDirect() {
        return !(this instanceof RegistryKey<T>);
    }

    default @Nullable RegistryKey<T> asKey() {
        return this instanceof RegistryKey<T> ? (RegistryKey<T>) this : null;
    }

    @SuppressWarnings("unchecked")
    default @Nullable T asValue() {
        return this instanceof RegistryKey<T> ? null : (T) this;
    }

    @SuppressWarnings("unchecked")
    default Either<RegistryKey<T>, T> unwrap() {
        if (this instanceof RegistryKey<T> key) {
            return Either.left(key);
        } else {
            return Either.right((T) this);
        }
    }

    @SuppressWarnings("unchecked")
    default @Nullable T resolve(DynamicRegistry<T> registry) {
        final var key = asKey();
        if (key != null) {
            return registry.get(key);
        } else {
            return (T) this;
        }
    }

}
