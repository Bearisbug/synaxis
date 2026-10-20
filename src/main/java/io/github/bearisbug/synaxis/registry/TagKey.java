package io.github.bearisbug.synaxis.registry;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.KeyPattern;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.ApiStatus;

public sealed interface TagKey<T> extends Keyed permits TagKeyImpl {
    static <T> TagKey<T> ofHash(String hashedKey) {
        if (!hashedKey.startsWith("#"))
            throw new IllegalArgumentException("Hashed key must start with '#': " + hashedKey);
        return new TagKeyImpl<>(Key.key(hashedKey.substring(1)));
    }

    /**
     * Creates a tag key from an unhashed key. Should not be used externally.
     */
    @ApiStatus.Internal
    static <T> TagKey<T> unsafeOf(@KeyPattern String key) {
        return unsafeOf(Key.key(key));
    }

    /**
     * Creates a tag key from an unhashed key. Should not be used externally.
     */
    @ApiStatus.Internal
    static <T> TagKey<T> unsafeOf(Key key) {
        return new TagKeyImpl<>(key);
    }

    default String hashedKey() {
        return "#" + key().asString();
    }


}
