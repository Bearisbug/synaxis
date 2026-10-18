package io.github.bearisbug.synaxis.registry;

import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.gamedata.DataPack;
import io.github.bearisbug.synaxis.network.packet.server.common.TagsPacket;
import io.github.bearisbug.synaxis.utils.collection.ObjectArray;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A registry for holding static vanilla registry data. Not generally user modifiable, always immutable: its entries
 * and its tags come with the bundled data and are shared by the whole JVM.
 */
@ApiStatus.Internal
final class StaticRegistry<T extends StaticProtocolObject<T>> implements Registry<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger(StaticRegistry.class);

    private final Key key;
    private final Map<Key, T> keyToValue;
    private final Map<T, RegistryKey<T>> valueToKey;
    private final List<T> idToValue;

    private final Map<TagKey<T>, RegistryTagImpl.Backed<T>> tags;

    StaticRegistry(
            Key key,
            Map<Key, T> namespaces,
            ObjectArray<T> ids,
            Map<TagKey<T>, RegistryTagImpl.Backed<T>> tags
    ) {
        this.key = key;
        this.keyToValue = Map.copyOf(namespaces);
        var valueToKey = new HashMap<T, RegistryKey<T>>(namespaces.size());
        for (var entry : namespaces.entrySet())
            valueToKey.put(entry.getValue(), new RegistryKeyImpl<>(entry.getKey()));
        this.valueToKey = Map.copyOf(valueToKey);
        this.idToValue = ids.toList();
        this.tags = Map.copyOf(tags);
    }

    @Override
    public Key key() {
        return this.key;
    }

    @Override
    public @Nullable T get(int id) {
        return byId(id);
    }

    @Override
    public @Nullable T get(Key key) {
        return this.keyToValue.get(key);
    }

    @Override
    public @Nullable RegistryKey<T> getKey(int id) {
        final T value = byId(id);
        return value == null ? null : new RegistryKeyImpl<>(value.key());
    }

    @Override
    public @Nullable RegistryKey<T> getKey(T value) {
        return this.valueToKey.get(value);
    }

    @Override
    public @Nullable RegistryKey<T> getKey(Key key) {
        return this.keyToValue.containsKey(key) ? new RegistryKeyImpl<>(key) : null;
    }

    @Override
    public int getId(RegistryKey<T> key) {
        final T value = this.keyToValue.get(key.key());
        return value == null ? -1 : value.id();
    }

    @Override
    public @Nullable DataPack getPack(int id) {
        // Static registries are always in the core data pack
        return byId(id) != null ? DataPack.MINECRAFT_CORE : null;
    }

    private @Nullable T byId(int id) {
        if (id < 0 || id >= this.idToValue.size()) return null;
        return this.idToValue.get(id);
    }

    @Override
    public int size() {
        return this.keyToValue.size();
    }

    @Override
    public Collection<RegistryKey<T>> keys() {
        return this.valueToKey.values();
    }

    @Override
    public Set<T> values() {
        return this.valueToKey.keySet();
    }

    @Override
    public @Nullable RegistryTag<T> getTag(TagKey<T> key) {
        return this.tags.get(key);
    }

    @Override
    public RegistryTag<T> getOrCreateTag(TagKey<T> key) {
        final RegistryTag<T> tag = this.tags.get(key);
        if (tag == null) throw new IllegalStateException("Static registry " + this.key.asString() + " has no tag "
                + key.key().asString() + "; static registry tags are fixed by the bundled data");
        return tag;
    }

    @Override
    public boolean removeTag(TagKey<T> key) {
        throw new UnsupportedOperationException("Static registry " + this.key.asString()
                + " cannot remove tag " + key.key().asString() + "; static registry tags are fixed by the bundled data");
    }

    @Override
    public Collection<RegistryTag<T>> tags() {
        return Collections.unmodifiableCollection(this.tags.values());
    }

    @Override
    public TagsPacket.Registry tagRegistry() {
        final List<TagsPacket.Tag> tagList = new ArrayList<>(tags.size());
        for (final RegistryTagImpl.Backed<T> tag : tags.values()) {
            final int[] entries = new int[tag.size()];
            int i = 0;
            for (var staticEntry : tag) {
                final int id = staticEntry instanceof StaticProtocolObject<T> po
                        ? po.id() : getId(staticEntry);
                if (id < 0) {
                    // getId returns -1 for a key that is not in the registry, which would go on the wire as a
                    // registry index and desynchronise the client
                    LOGGER.warn("Tag {} of registry {} references unknown entry {}",
                            tag.key().key().asString(), key.asString(), staticEntry.key().asString());
                    continue;
                }
                entries[i++] = id;
            }
            tagList.add(new TagsPacket.Tag(tag.key().key().asString(),
                    i == entries.length ? entries : Arrays.copyOf(entries, i)));
        }
        return new TagsPacket.Registry(key().asString(), tagList);
    }

}
