package dev.gameengine.component;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import dev.gameengine.codec.Codec;
import dev.gameengine.codec.Result;
import dev.gameengine.codec.Transcoder;
import dev.gameengine.codec.Transcoder.MapLike;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.utils.validate.Check;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * <p>A component list, always stored as a patch of added and removed components (even if none are removed).</p>
 *
 * <p>The inner map contains the value for added components, null for removed components, and no entry for unmodified components.</p>
 *
 * @param components The component patch.
 */
record DataComponentMapImpl(Int2ObjectMap<@Nullable Object> components) implements DataComponentMap {
    private static final char REMOVAL_PREFIX = '!';

    static DataComponentMap fromMap(Int2ObjectMap<@Nullable Object> components) {
        return components.isEmpty() ? DataComponentMap.EMPTY : new DataComponentMapImpl(components);
    }

    @Override
    public boolean isEmpty() {
        return components.isEmpty();
    }

    @Override
    public boolean has(DataComponent<?> component) {
        return components.containsKey(component.id()) && components.get(component.id()) != null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @Nullable T get(DataComponent<T> component) {
        return (T) components.get(component.id());
    }

    @Override
    public boolean has(DataComponentMap prototype, DataComponent<?> component) {
        if (components.containsKey(component.id())) {
            return components.get(component.id()) != null;
        } else {
            return prototype.has(component);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @Nullable T get(DataComponentMap prototype, DataComponent<T> component) {
        if (components.containsKey(component.id())) {
            return (T) components.get(component.id());
        } else {
            return prototype.get(component);
        }
    }

    @Override
    public <T> DataComponentMap set(DataComponent<T> component, T value) {
        Int2ObjectMap<Object> newComponents = new Int2ObjectArrayMap<>(components);
        newComponents.put(component.id(), component.freeze(value));
        return new DataComponentMapImpl(newComponents);
    }

    @Override
    public DataComponentMap remove(DataComponent<?> component) {
        Int2ObjectMap<@Nullable Object> newComponents = new Int2ObjectArrayMap<>(components);
        newComponents.put(component.id(), null);
        return new DataComponentMapImpl(newComponents);
    }

    @Override
    public DataComponentMap reset(DataComponent<?> component) {
        if (!components.containsKey(component.id())) return this;
        Int2ObjectMap<@Nullable Object> newComponents = new Int2ObjectArrayMap<>(components);
        newComponents.remove(component.id());
        return fromMap(newComponents);
    }

    @Override
    public List<DataComponent.Value> entrySet() {
        if (components.isEmpty()) return List.of();
        final List<DataComponent.Value> entries = new ArrayList<>(components.size());
        for (var entry : components.int2ObjectEntrySet())
            entries.add(new DataComponent.Value(DataComponent.fromId(entry.getIntKey()), entry.getValue()));
        return entries;
    }

    @Override
    public Builder toBuilder() {
        return new BuilderImpl(new Int2ObjectArrayMap<>(components));
    }

    @Override
    public PatchBuilder toPatchBuilder() {
        return new PatchBuilderImpl(new Int2ObjectArrayMap<>(components));
    }

    record BuilderImpl(Int2ObjectMap<@Nullable Object> components) implements DataComponentMap.Builder {

        @Override
        public boolean has(DataComponent<?> component) {
            return components.get(component.id()) != null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> @Nullable T get(DataComponent<T> component) {
            return (T) components.get(component.id());
        }

        @Override
        public <T> Builder set(DataComponent<T> component, T value) {
            components.put(component.id(), component.freeze(value));
            return this;
        }

        @Override
        public DataComponentMap build() {
            return fromMap(new Int2ObjectArrayMap<>(components));
        }
    }

    record PatchBuilderImpl(Int2ObjectMap<@Nullable Object> components) implements DataComponentMap.PatchBuilder {

        @Override
        public boolean has(DataComponent<?> component) {
            return components.get(component.id()) != null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> @Nullable T get(DataComponent<T> component) {
            return (T) components.get(component.id());
        }

        @Override
        public <T> PatchBuilder set(DataComponent<T> component, T value) {
            components.put(component.id(), component.freeze(value));
            return this;
        }

        @Override
        public PatchBuilder remove(DataComponent<?> component) {
            components.put(component.id(), null);
            return this;
        }

        @Override
        public PatchBuilder reset(DataComponent<?> component) {
            components.remove(component.id());
            return this;
        }

        @Override
        public DataComponentMap build() {
            return fromMap(new Int2ObjectArrayMap<>(components));
        }
    }

    record NetworkTypeImpl(
            IntFunction<@Nullable DataComponent<?>> idToType,
            boolean isPatch, boolean isTrusted
    ) implements NetworkBuffer.Type<DataComponentMap> {
        /** Vanilla never sends more than this many added or removed components in one patch. */
        private static final int MAX_COMPONENTS = 256;

        @Override
        public void write(NetworkBuffer buffer, DataComponentMap value) {
            final DataComponentMapImpl patch = (DataComponentMapImpl) value;
            int added = 0, removed = 0;
            for (var entry : patch.components.int2ObjectEntrySet()) {
                if (isNotSynced(entry.getIntKey())) continue;
                if (entry.getValue() != null) added++;
                else removed++;
            }

            buffer.write(NetworkBuffer.VAR_INT, added);
            if (isPatch) {
                buffer.write(NetworkBuffer.VAR_INT, removed);
            }
            for (var entry : patch.components.int2ObjectEntrySet()) {
                if (entry.getValue() == null || isNotSynced(entry.getIntKey())) continue;

                buffer.write(NetworkBuffer.VAR_INT, entry.getIntKey());
                @SuppressWarnings("unchecked")
                DataComponent<Object> type = (DataComponent<@NotNull Object>) this.idToType.apply(entry.getIntKey());
                Check.notNull(type, "Unknown component id: {0}", entry.getIntKey());
                if (isTrusted) {
                    type.write(buffer, entry.getValue());
                } else {
                    // Need to length prefix it, so write to another buffer first then copy.
                    final byte[] componentData = NetworkBuffer.makeArray(b -> type.write(b, entry.getValue()), buffer.registries());
                    buffer.write(NetworkBuffer.BYTE_ARRAY, componentData);
                }
            }
            if (isPatch) {
                for (var entry : patch.components.int2ObjectEntrySet()) {
                    if (entry.getValue() != null || isNotSynced(entry.getIntKey())) continue;

                    buffer.write(NetworkBuffer.VAR_INT, entry.getIntKey());
                }
            }
        }

        /**
         * Components registered without a network type never reach the client, so they must not be counted or
         * written. Unknown ids are left in so that the write loop still reports them.
         */
        private boolean isNotSynced(int id) {
            final DataComponent<?> type = this.idToType.apply(id);
            return type != null && !type.isSynced();
        }

        @Override
        public DataComponentMap read(NetworkBuffer buffer) {
            final int added = buffer.read(NetworkBuffer.VAR_INT);
            final int removed = isPatch ? buffer.read(NetworkBuffer.VAR_INT) : 0;
            Check.stateCondition(added < 0 || added > MAX_COMPONENTS, "Invalid added component count: {0}", added);
            Check.stateCondition(removed < 0 || removed > MAX_COMPONENTS, "Invalid removed component count: {0}", removed);
            Int2ObjectMap<@Nullable Object> patch = new Int2ObjectArrayMap<>(added + removed);
            for (int i = 0; i < added; i++) {
                final int id = buffer.read(NetworkBuffer.VAR_INT);
                @SuppressWarnings("unchecked")
                DataComponent<Object> type = (DataComponent<@NotNull Object>) this.idToType.apply(id);
                Check.notNull(type, "Unknown component: {0}", id);
                Check.stateCondition(patch.containsKey(type.id()), "Duplicate component: {0}", id);
                if (isTrusted) {
                    patch.put(type.id(), type.read(buffer));
                } else {
                    // A view over the declared range, so a component cannot read past its own data
                    // and a declared length cannot make the server copy bytes it never received.
                    final int length = buffer.read(NetworkBuffer.VAR_INT);
                    Check.argCondition(length < 0, "Component data length cannot be negative: {0}", length);
                    buffer.ensureReadable(length);
                    final long offset = buffer.advanceRead(length);
                    final NetworkBuffer componentData = buffer.slice(offset, length, 0, length).readOnly();
                    patch.put(type.id(), type.read(componentData));
                }
            }
            for (int i = 0; i < removed; i++) {
                final int id = buffer.read(NetworkBuffer.VAR_INT);
                final DataComponent<?> type = this.idToType.apply(id);
                Check.notNull(type, "Unknown component: {0}", id);
                Check.stateCondition(patch.containsKey(type.id()), "Duplicate component: {0}", id);
                patch.put(type.id(), null);
            }
            return new DataComponentMapImpl(patch);
        }
    }

    record CodecImpl(
            IntFunction<@Nullable DataComponent<?>> idToType,
            Function<String, @Nullable DataComponent<?>> nameToType,
            boolean isPatch
    ) implements Codec<DataComponentMap> {
        CodecImpl {
            Objects.requireNonNull(idToType, "idToType");
            Objects.requireNonNull(nameToType, "nameToType");
        }

        @Override
        public <D> Result<DataComponentMap> decode(Transcoder<D> coder, D value) {
            final Result<MapLike<D>> mapResult = coder.getMap(value);
            if (!(mapResult instanceof Result.Ok(var map)))
                return mapResult.cast();
            if (map.isEmpty()) return new Result.Ok<>(EMPTY);

            final Int2ObjectMap<@Nullable Object> patch = new Int2ObjectArrayMap<>(map.size());
            for (String key : map.keys()) {
                boolean remove = false;
                if (!key.isEmpty() && key.charAt(0) == REMOVAL_PREFIX) {
                    key = key.substring(1);
                    remove = true;
                }
                final DataComponent<?> type = this.nameToType.apply(key);
                if (type == null) return new Result.Error<>("unknown data component: " + key);

                if (remove) {
                    if (isPatch) patch.put(type.id(), null);
                    // Removing a component in an absolute (non-patch) builder is a noop because it is not yet present.
                } else {
                    switch (map.getValue(key).map(v -> type.decode(coder, v))) {
                        case Result.Ok(Object componentData) -> patch.put(type.id(), componentData);
                        case Result.Error<?>(String message) -> {
                            return new Result.Error<>(type.name() + ": " + message);
                        }
                    }
                }
            }

            return new Result.Ok<>(fromMap(patch));
        }

        @Override
        public <D> Result<D> encode(Transcoder<D> coder, @Nullable DataComponentMap value) {
            if (value == null) return new Result.Error<>("null");
            final DataComponentMapImpl patch = (DataComponentMapImpl) value;

            final Transcoder.MapBuilder<D> map = coder.createMap();
            for (var entry : patch.components.int2ObjectEntrySet()) {
                @SuppressWarnings("unchecked")
                DataComponent<Object> type = (DataComponent<@NotNull Object>) this.idToType.apply(entry.getIntKey());
                if (type == null) return new Result.Error<>("unknown data component id: " + entry.getIntKey());
                // Components registered without a codec cannot be serialized
                if (!type.isSerialized()) continue;
                if (entry.getValue() == null) {
                    if (isPatch) map.put(REMOVAL_PREFIX + type.name(), coder.createMap().build());
                    // Removing a component in an absolute (non-patch) builder is a noop because it is not yet present.
                } else {
                    switch (type.encode(coder, entry.getValue())) {
                        case Result.Ok(D componentValue) -> map.put(type.name(), componentValue);
                        case Result.Error<?>(String message) -> {
                            return new Result.Error<>(type.name() + ": " + message);
                        }
                    }
                }
            }

            return new Result.Ok<>(map.build());
        }
    }

}
