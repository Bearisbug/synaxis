package io.github.bearisbug.synaxis.registry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.gamedata.DataPack;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.TagsPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.RegistryDataPacket;
import io.github.bearisbug.synaxis.utils.json.JsonUtil;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The list and hash map backed {@link DynamicRegistry}. Its thread contract:
 * <ul>
 *     <li>Writes ({@code register}, {@code remove}) are serialized on {@code REGISTRY_LOCK}, one lock shared by every
 *     registry, which also guards switching {@code registries} in {@link #registryDataPacket(Registries, boolean)}.</li>
 *     <li>Reads take no lock. They are consistent once the registry is frozen, or when every write happens-before
 *     {@code ServerProcess#start}.</li>
 *     <li>With {@code ServerConfig#registryUnsafeOps} or {@code insideTest} the registry is never frozen; a read
 *     racing a write can then see an id of -1 or a null entry.</li>
 *     <li>{@link #keys()} and {@link #values()} are live views: a write while one is iterated throws
 *     {@link java.util.ConcurrentModificationException}.</li>
 * </ul>
 */
@ApiStatus.Internal
final class DynamicRegistryImpl<T> implements DynamicRegistry<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger(DynamicRegistryImpl.class);

    // Writes only happen while registries are set up, so one lock for all of them costs nothing
    private static final Object REGISTRY_LOCK = new Object();

    private volatile @Nullable Registries registries = null;
    private volatile boolean frozen = false;
    // Set by the process owning these registries so it can drop its cached tags packet.
    private volatile Runnable tagChangeListener = () -> {
    };
    private final CachedPacket vanillaRegistryDataPacket = new CachedPacket(() -> createRegistryDataPacket(registries, true));

    private final List<T> idToValue;
    private final List<RegistryKey<T>> idToKey;
    private final Map<RegistryKey<T>, Integer> keyToId;
    private final Map<Key, T> keyToValue;
    private final Map<T, RegistryKey<T>> valueToKey;
    private final List<DataPack> packById;

    private final Map<TagKey<T>, RegistryTagImpl.Backed<T>> tags;

    private final Key key;
    private final Codec<T> codec;

    DynamicRegistryImpl(Key key, @Nullable Codec<T> codec) {
        this.key = key;
        this.codec = codec;
        this.idToValue = new ArrayList<>();
        this.idToKey = new ArrayList<>();
        this.keyToId = new HashMap<>();
        this.keyToValue = new HashMap<>();
        this.valueToKey = new HashMap<>();
        this.packById = new ArrayList<>();
        // Tags are always mutable across the lock.
        this.tags = new ConcurrentHashMap<>();
    }

    @Override
    public Key key() {
        return this.key;
    }

    @Override
    public @Nullable T get(int id) {
        if (id < 0 || id >= idToValue.size())
            return null;
        return idToValue.get(id);
    }

    @Override
    public @Nullable T get(Key key) {
        return keyToValue.get(key);
    }

    @Override
    public @Nullable RegistryKey<T> getKey(int id) {
        if (id < 0 || id >= idToKey.size())
            return null;
        return idToKey.get(id);
    }

    @Override
    public @Nullable RegistryKey<T> getKey(T value) {
        return valueToKey.get(value);
    }

    @Override
    public @Nullable RegistryKey<T> getKey(Key key) {
        if (!keyToValue.containsKey(key))
            return null;
        return new RegistryKeyImpl<>(key);
    }

    @Override
    public int getId(RegistryKey<T> key) {
        return keyToId.getOrDefault(key, -1);
    }

    @Override
    public RegistryKey<T> register(Key key, T object, DataPack pack) {
        if (frozen) throw new IllegalStateException(frozen("register"));
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(object, "Object cannot be null");
        Objects.requireNonNull(pack, "Pack cannot be null");

        final RegistryKey<T> registryKey = new RegistryKeyImpl<>(key);
        synchronized (REGISTRY_LOCK) {
            Integer id = keyToId.get(registryKey);
            keyToValue.put(key, object);
            if (id == null) {
                valueToKey.put(object, registryKey);
                idToValue.add(object);
                idToKey.add(registryKey);
                keyToId.put(registryKey, idToValue.size() - 1);
                packById.add(pack);
            } else {
                final T previous = idToValue.set(id, object);
                // The replaced value is no longer registered, so it must not resolve to a key anymore
                valueToKey.remove(previous);
                valueToKey.put(object, registryKey);
                idToKey.set(id, registryKey);
                keyToId.put(registryKey, id);
                packById.set(id, pack);
            }

            vanillaRegistryDataPacket.invalidate();
            return registryKey;
        }
    }

    @Override
    public boolean remove(Key key) {
        if (frozen) throw new IllegalStateException(frozen("remove"));
        Objects.requireNonNull(key, "Key cannot be null");

        final RegistryKey<T> registryKey = new RegistryKeyImpl<>(key);
        synchronized (REGISTRY_LOCK) {
            Integer idObject = keyToId.get(registryKey);
            if (idObject == null) return false;
            int id = idObject;

            // Remove value from all mappings (shifting down indices)
            idToValue.remove(id);
            idToKey.remove(registryKey);
            keyToId.remove(registryKey);
            var value = keyToValue.remove(key);
            valueToKey.remove(value);
            packById.remove(id);

            // The lists were compacted, so every entry after the removed one now sits one index lower
            for (int i = id; i < idToKey.size(); i++) {
                keyToId.put(idToKey.get(i), i);
            }

            // Remove all references from tags
            for (final var tag : tags.values()) {
                tag.remove(registryKey);
            }
            // Tags are sent as ids, and every entry after the removed one now sits one id lower,
            // so a cached tags packet is stale even when the removed entry was in no tag
            tagChangeListener.run();

            vanillaRegistryDataPacket.invalidate();
            return true;
        }
    }

    @Override
    public @Nullable DataPack getPack(int id) {
        if (id < 0 || id >= packById.size())
            return null;
        return packById.get(id);
    }

    @Override
    public int size() {
        return idToValue.size();
    }

    @Override
    public Collection<RegistryKey<T>> keys() {
        return Collections.unmodifiableCollection(idToKey);
    }

    @Override
    public Collection<T> values() {
        return Collections.unmodifiableCollection(idToValue);
    }

    // Tags

    @Override
    public @Nullable RegistryTag<T> getTag(TagKey<T> key) {
        return this.tags.get(key);
    }

    @Override
    public RegistryTag<T> getOrCreateTag(TagKey<T> key) {
        final RegistryTag<T> existing = this.tags.get(key);
        if (existing != null) return existing;
        if (frozen) throw new IllegalStateException(frozen("creating tag " + key.key().asString()));
        final RegistryTag<T> created = this.tags.computeIfAbsent(key, RegistryTagImpl.Backed::new);
        tagChangeListener.run();
        return created;
    }

    @Override
    public boolean removeTag(TagKey<T> key) {
        if (frozen) throw new IllegalStateException(frozen("removing tag " + key.key().asString()));
        if (this.tags.remove(key) == null) return false;
        tagChangeListener.run();
        return true;
    }

    @Override
    public Collection<RegistryTag<T>> tags() {
        return Collections.unmodifiableCollection(this.tags.values());
    }

    @Override // This method is called by a virtual thread in the configuration phase
    public SendablePacket registryDataPacket(Registries registries, boolean excludeVanilla) {
        // We cache the vanilla packet because that is by far the most common case. If some client claims not to have
        // the vanilla datapack we can compute the entire thing.
        if (excludeVanilla) {
            if (this.registries != registries) {
                synchronized (REGISTRY_LOCK) {
                    if (this.registries != registries) {
                        this.registries = registries;
                        vanillaRegistryDataPacket.invalidate();
                    }
                }
            }
            return vanillaRegistryDataPacket;
        }

        return createRegistryDataPacket(registries, false);
    }

    @Override
    public TagsPacket.Registry tagRegistry() {
        final List<TagsPacket.Tag> tagList = new ArrayList<>(tags.size());
        for (final RegistryTagImpl.Backed<T> tag : tags.values()) {
            // Snapshot first: the tag is a concurrent set, so its size can change while it is iterated
            final List<RegistryKey<T>> tagKeys = new ArrayList<>();
            for (var registryKey : tag) tagKeys.add(registryKey);

            final int[] entries = new int[tagKeys.size()];
            int i = 0;
            for (var registryKey : tagKeys) {
                final Integer id = keyToId.get(registryKey);
                if (id == null) {
                    LOGGER.warn("Tag {} of registry {} references unknown entry {}",
                            tag.key().key().asString(), key().asString(), registryKey.key().asString());
                    continue;
                }
                entries[i++] = id;
            }
            tagList.add(new TagsPacket.Tag(tag.key().key().asString(),
                    i == entries.length ? entries : Arrays.copyOf(entries, i)));
        }
        return new TagsPacket.Registry(key().asString(), tagList);
    }

    private RegistryDataPacket createRegistryDataPacket(Registries registries, boolean excludeVanilla) {
        Objects.requireNonNull(codec, "Cannot create registry data packet for server-only registry");
        Transcoder<BinaryTag> transcoder = new RegistryTranscoder<>(Transcoder.NBT, registries);
        // Copy to avoid concurrent modification issues while iterating, as we are not synchronized on the registry
        final List<T> idToValue;
        final List<RegistryKey<T>> idToKey;
        final List<DataPack> packById;
        if (!frozen) {
            synchronized (REGISTRY_LOCK) {
                idToValue = List.copyOf(this.idToValue);
                idToKey = List.copyOf(this.idToKey);
                packById = List.copyOf(this.packById);
            }
        } else {
            idToValue = this.idToValue;
            idToKey = this.idToKey;
            packById = this.packById;
        }
        List<RegistryDataPacket.Entry> entries = new ArrayList<>(idToValue.size());
        for (int i = 0; i < idToValue.size(); i++) {
            CompoundBinaryTag data = null;
            // Only entries of the minecraft:core pack leave their data out when excluding vanilla, every other
            // entry is sent with its full NBT
            T entry = idToValue.get(i);
            DataPack pack = packById.get(i);
            if (!excludeVanilla || pack != DataPack.MINECRAFT_CORE) {
                data = switch (codec.encode(transcoder, entry)) {
                    case Result.Ok(BinaryTag tag) -> (CompoundBinaryTag) tag;
                    case Result.Error(String message) -> throw new IllegalStateException(
                            "Failed to encode registry entry " + i + " (" + idToKey.get(i) + ") for registry " + key + ": " + message);
                };
            }
            entries.add(new RegistryDataPacket.Entry(idToKey.get(i).key().asString(), data));
        }
        return new RegistryDataPacket(key.asString(), entries);
    }

    void freeze() {
        this.frozen = true;
    }

    void tagChangeListener(Runnable listener) {
        this.tagChangeListener = listener;
    }

    private String frozen(String operation) {
        return "Registry " + key.asString() + " is frozen: " + operation + " after ServerProcess#start is rejected. "
                + "Register entries before start(); ServerConfig#registryUnsafeOps lifts this, but connected clients "
                + "never receive runtime changes";
    }

    /**
     * Loads the entries of an opaque registry from the pre-encoded NBT under {@code /data/opaque}.
     * The tag is stored exactly as the client receives it, so no server-side type is needed.
     */
    @SuppressWarnings({"removal", "unchecked"})
    void loadOpaqueRegistry() {
        tags.putAll(RegistryData.loadTags(key()));
        final String path = String.format("opaque/%s.nbt", key().value());
        try (InputStream resourceStream = RegistryData.loadRegistryFile(path)) {
            Check.notNull(resourceStream, "Opaque registry resource {0} does not exist!", path);
            final CompoundBinaryTag root = BinaryTagIO.reader().read(resourceStream, BinaryTagIO.Compression.NONE);
            for (BinaryTag entry : root.getList("entries", BinaryTagTypes.COMPOUND)) {
                final CompoundBinaryTag compound = (CompoundBinaryTag) entry;
                register(compound.getString("id"), (T) compound.getCompound("data"), DataPack.MINECRAFT_CORE);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("removal")
    void loadStaticJsonRegistry(@Nullable Registries registries, @Nullable Comparator<String> idComparator, Codec<T> codec) {
        // Tags must exist before entries are decoded because registry codecs can resolve tags while loading values.
        tags.putAll(RegistryData.loadTags(key()));
        try (InputStream resourceStream = RegistryData.loadRegistryFile(String.format("%s.json", key().value()))) {
            Check.notNull(resourceStream, "Registry resource {0}.json does not exist!", key().value());
            final JsonElement json = JsonUtil.fromJson(new InputStreamReader(resourceStream, StandardCharsets.UTF_8));
            if (!(json instanceof JsonObject root))
                throw new IllegalStateException("Failed to load registry " + key() + ": expected a JSON object, got " + json);

            final Transcoder<JsonElement> transcoder = registries != null ? new RegistryTranscoder<>(Transcoder.JSON, registries) : Transcoder.JSON;
            List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(root.entrySet());
            if (idComparator != null) entries.sort(Map.Entry.comparingByKey(idComparator));
            for (Map.Entry<String, JsonElement> entry : entries) {
                final String namespace = entry.getKey();
                final Result<T> valueResult = codec.decode(transcoder, entry.getValue());
                if (valueResult instanceof Result.Ok(T value)) {
                    register(namespace, value, DataPack.MINECRAFT_CORE);
                } else {
                    throw new IllegalStateException("Failed to decode registry entry " + namespace + " for registry " + key() + ": " + valueResult.cast().message());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
