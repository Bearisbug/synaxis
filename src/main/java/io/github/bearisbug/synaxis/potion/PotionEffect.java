package io.github.bearisbug.synaxis.potion;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.KeyPattern;
import net.kyori.adventure.translation.Translatable;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.Registry;
import io.github.bearisbug.synaxis.registry.RegistryData;
import io.github.bearisbug.synaxis.registry.StaticProtocolObject;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Objects;

public sealed interface PotionEffect extends StaticProtocolObject<PotionEffect>, PotionEffects,
        Translatable permits PotionEffectImpl {
    NetworkBuffer.Type<PotionEffect> NETWORK_TYPE = NetworkBuffer.VAR_INT.transform(
            id -> Objects.requireNonNull(PotionEffect.fromId(id), () -> "unknown potion effect id: " + id), PotionEffect::id);
    Codec<PotionEffect> CODEC = Codec.KEY.transform(
            key -> Objects.requireNonNull(fromKey(key), () -> "unknown potion effect: " + key.asString()), PotionEffect::key);

    /**
     * Returns the legacy registry data backing this potion effect.
     *
     * @return the legacy registry data
     * @deprecated use the direct accessors on {@link PotionEffect}
     */
    @Deprecated(forRemoval = true)
    @SuppressWarnings("removal")
    @Override
    @Contract(pure = true)
    RegistryData.PotionEffectEntry registry();

    @Override
    default Key key() {
        return registry().key();
    }

    @Override
    default int id() {
        return registry().id();
    }

    @Override
    default String translationKey() {
        return registry().translationKey();
    }

    /**
     * Returns the display color of this potion effect.
     *
     * @return the RGB color
     */
    @Contract(pure = true)
    default int color() {
        return registry().color();
    }

    /**
     * Returns whether this potion effect applies instantaneously.
     *
     * @return {@code true} if this effect is instantaneous
     */
    @Contract(pure = true)
    default boolean instantaneous() {
        return registry().isInstantaneous();
    }

    static Collection<PotionEffect> values() {
        return PotionEffectImpl.REGISTRY.values();
    }

    static @Nullable PotionEffect fromKey(@KeyPattern String key) {
        try {
            return fromKey(Key.key(key));
        } catch (InvalidKeyException _) {
            return null;
        }
    }

    static @Nullable PotionEffect fromKey(Key key) {
        return PotionEffectImpl.REGISTRY.get(key);
    }

    static @Nullable PotionEffect fromId(int id) {
        return PotionEffectImpl.REGISTRY.get(id);
    }

    static Registry<PotionEffect> staticRegistry() {
        return PotionEffectImpl.REGISTRY;
    }
}
