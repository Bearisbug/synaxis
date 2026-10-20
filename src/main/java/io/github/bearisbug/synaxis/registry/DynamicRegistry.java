package io.github.bearisbug.synaxis.registry;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.gamedata.DataPack;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

/**
 * <p>Holds registry data for any of the registries controlled by the server. Entries in registries should be referenced
 * using a {@link RegistryKey} object as opposed to the record type. For example, a biome should be stored as
 * `RegistryKey Biome`, as opposed to `Biome` directly.</p>
 *
 * <p>Builtin registries should be accessed via a {@link Registries} instance (currently implemented by
 * {@link io.github.bearisbug.synaxis.ServerProcess}.</p>
 *
 * @param <T> The type of the registry entries
 * @see Registries
 */
public sealed interface DynamicRegistry<T> extends Registry<T> permits DynamicRegistryImpl {

    @SafeVarargs
    static <T> DynamicRegistry<T> fromMap(Key key, Map.Entry<Key, T>... entries) {
        var registry = new DynamicRegistryImpl<T>(key, null);
        for (var entry : entries)
            registry.register(entry.getKey(), entry.getValue(), DataPack.ENGINE_UNNAMED);
        return registry;
    }

    /**
     * Creates a new empty registry without a codec, so it is never sent to the client. Reads no data.
     */
    @ApiStatus.Internal
    static <T> DynamicRegistry<T> create(Key key) {
        return new DynamicRegistryImpl<>(key, null);
    }

    /**
     * Creates a new empty registry of the given type. Reads no data. Should only be used internally.
     *
     * @see Registries
     */
    @ApiStatus.Internal
    static <T> DynamicRegistry<T> create(Key key, Codec<T> codec) {
        return new DynamicRegistryImpl<>(key, codec);
    }

    /**
     * Creates a registry prefilled from the vendored {@code /data/<key>.json} and its tags, see
     * {@link #create(RegistryKey, Codec, Registries, Comparator, Codec)}. Should only be used internally.
     *
     * @see Registries
     */
    @ApiStatus.Internal
    static <T> DynamicRegistry<T> create(RegistryKey<Registry<T>> key, Codec<T> codec) {
        return create(key, codec, null, null, null);
    }

    /**
     * Creates a registry prefilled from the vendored {@code /data/<key>.json} and its tags, see
     * {@link #create(RegistryKey, Codec, Registries, Comparator, Codec)}. Should only be used internally.
     *
     * @see Registries
     */
    @ApiStatus.Internal
    static <T> DynamicRegistry<T> create(RegistryKey<Registry<T>> key, Codec<T> codec, @Nullable Registries registries) {
        return create(key, codec, registries, null, null);
    }

    /**
     * Creates a registry prefilled from the vendored {@code /data/<key>.json} on the classpath (or the working
     * directory) and its tags, the entries decoded with {@code readCodec} or else {@code codec}. Throws when the file
     * is missing or an entry does not decode. Should only be used internally.
     *
     * @see Registries
     */
    @ApiStatus.Internal
    static <T> DynamicRegistry<T> create(RegistryKey<Registry<T>> key, Codec<T> codec, @Nullable Registries registries, @Nullable Comparator<String> idComparator, @Nullable Codec<T> readCodec) {
        final DynamicRegistryImpl<T> registry = new DynamicRegistryImpl<>(key.key(), codec);
        registry.loadStaticJsonRegistry(registries, idComparator, Objects.requireNonNullElse(readCodec, codec));
        return registry;
    }

    /**
     * Creates a registry whose entries are kept as the raw NBT the client receives, with no server-side type.
     *
     * @see Registries#opaque(RegistryKey)
     */
    @ApiStatus.Internal
    static DynamicRegistry<BinaryTag> createOpaque(RegistryKey<Registry<BinaryTag>> key) {
        final DynamicRegistryImpl<BinaryTag> registry = new DynamicRegistryImpl<>(key.key(), Codec.NBT);
        registry.loadOpaqueRegistry();
        return registry;
    }

    /**
     * <p>Register an object to this registry, overwriting the previous entry if any is present.</p>
     *
     * <p>An id without a namespace is read as {@code minecraft:}: {@code register("plains", …)} overwrites the vanilla
     * {@code minecraft:plains}, so a custom entry should carry its own namespace. An overwritten vanilla entry no
     * longer counts as vanilla, so the registry data packet stops leaving it out and every client receives its full
     * data.</p>
     *
     * <p>Before the owning process starts, entries can be added, overwritten and removed freely. Once it has started
     * the registry is frozen and every write throws {@link IllegalStateException} (API-004), unless the process runs
     * with {@code ServerConfig#registryUnsafeOps}. Writes are then unsynchronized with readers: no tick thread or
     * connection in the configuration phase may read this registry while it is written.</p>
     *
     * <p>Note: the new registry will not be sent to existing players. They must be returned to
     * the configuration phase to receive new registry data. See {@link Player#startConfigurationPhase()}.</p>
     *
     * <p><b>WARNING:</b> Updating an existing entry is an inherently unsafe operation as it may cause desync with
     * existing structures.</p>
     *
     * @param id     The id of the entry
     * @param object The entry to register
     * @return the key of the registered entry
     * @throws IllegalStateException if the registry is frozen
     */
    default RegistryKey<T> register(String id, T object) {
        return register(Key.key(id), object, DataPack.ENGINE_UNNAMED);
    }

    /**
     * Registers an object under a key, see {@link #register(String, Object)}.
     *
     * @param id     The key of the entry
     * @param object The entry to register
     * @return the key of the registered entry
     * @throws IllegalStateException if the registry is frozen
     */
    default RegistryKey<T> register(Key id, T object) {
        return register(id, object, DataPack.ENGINE_UNNAMED);
    }

    @ApiStatus.Internal
    default RegistryKey<T> register(String id, T object, DataPack pack) {
        return register(Key.key(id), object, pack);
    }

    @ApiStatus.Internal
    RegistryKey<T> register(Key id, T object, DataPack pack);

    /**
     * <p>Removes an object from this registry.</p>
     *
     * <p><b>WARNING:</b> This operation will cause all subsequent IDs to be remapped, meaning that any loaded entry
     * with existing IDs may be incorrect. For example, loading a world with 0=plains, 1=desert, 2=badlands would store
     * those IDs in the palette. If you then deleted entry 1 (desert), any desert biomes in the loaded world would
     * become badlands, and any badlands would become invalid.</p>
     *
     * <p>Like any write, removing throws {@link IllegalStateException} once the owning process has started, unless it
     * runs with {@code ServerConfig#registryUnsafeOps} (see {@link #register(String, T)}); then no tick thread or
     * connection in the configuration phase may read this registry while it is written.</p>
     *
     * <p>Note: the new registry will not be sent to existing players. They must be returned to
     * the configuration phase to receive new registry data. See {@link Player#startConfigurationPhase()}.</p>
     *
     * @param key The id of the entry to remove
     * @return True if the object was removed, false if it was not present
     * @throws IllegalStateException if the registry is frozen
     */
    boolean remove(Key key);

    /**
     * <p>Returns a {@link SendablePacket} potentially excluding vanilla entries if possible. It is never possible to
     * exclude vanilla entries if one has been overridden (e.g. via {@link #register(Key, T)}.</p>
     *
     * @param registries     Registries provider
     * @param excludeVanilla Whether to exclude vanilla entries
     * @return A {@link SendablePacket} containing the registry data
     */
    @ApiStatus.Internal
    SendablePacket registryDataPacket(Registries registries, boolean excludeVanilla);

}
