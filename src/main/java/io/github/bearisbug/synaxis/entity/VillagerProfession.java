package io.github.bearisbug.synaxis.entity;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.KeyPattern;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.RegistryData;
import io.github.bearisbug.synaxis.registry.StaticProtocolObject;
import io.github.bearisbug.synaxis.sound.SoundEvent;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Objects;

public sealed interface VillagerProfession extends StaticProtocolObject<VillagerProfession>, VillagerProfessions permits VillagerProfessionImpl {

    NetworkBuffer.Type<VillagerProfession> NETWORK_TYPE = NetworkBuffer.VAR_INT.transform(
            id -> Objects.requireNonNull(VillagerProfession.fromId(id), () -> "unknown villager profession id: " + id), VillagerProfession::id);
    Codec<VillagerProfession> NBT_TYPE = Codec.KEY.transform(
            key -> Objects.requireNonNull(fromKey(key), () -> "unknown villager profession: " + key.asString()), VillagerProfession::key);

    @Override
    default Key key() {
        return registry().key();
    }

    @Override
    default int id() {
        return registry().id();
    }

    /**
     * Returns the legacy registry data backing this profession.
     *
     * @return the legacy registry data
     * @deprecated use the direct accessors on {@link VillagerProfession}
     */
    @Deprecated(forRemoval = true)
    @SuppressWarnings("removal")
    @Override
    @Contract(pure = true)
    RegistryData.VillagerProfessionEntry registry();

    /**
     * Returns the sound played while a villager works in this profession.
     *
     * @return the work sound, or {@code null} when absent
     */
    @Contract(pure = true)
    default @Nullable SoundEvent workSound() {
        return registry().workSound();
    }

    static Collection<VillagerProfession> values() {
        return VillagerProfessionImpl.REGISTRY.values();
    }

    static @Nullable VillagerProfession fromKey(@KeyPattern String key) {
        try {
            return fromKey(Key.key(key));
        } catch (InvalidKeyException _) {
            return null;
        }
    }

    static @Nullable VillagerProfession fromKey(Key key) {
        return VillagerProfessionImpl.REGISTRY.get(key);
    }

    static @Nullable VillagerProfession fromId(int id) {
        return VillagerProfessionImpl.REGISTRY.get(id);
    }

}
