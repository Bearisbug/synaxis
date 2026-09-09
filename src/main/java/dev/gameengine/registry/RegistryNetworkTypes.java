package dev.gameengine.registry;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.registry.RegistryTagImpl.Backed;
import dev.gameengine.registry.RegistryTagImpl.Direct;
import dev.gameengine.registry.RegistryTagImpl.Empty;
import dev.gameengine.utils.validate.Check;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class RegistryNetworkTypes {

    /**
     * @param holder whether the reference uses the vanilla holder framing, where the id is offset by one and zero
     *               marks an inline value. Registries without a server-side type cannot decode an inline value.
     */
    record RegistryKeyImpl<T>(Registries.Selector<T> selector, boolean holder) implements NetworkBuffer.Type<RegistryKey<T>> {
        @Override
        public void write(NetworkBuffer buffer, RegistryKey<T> value) {
            final var registries = Objects.requireNonNull(buffer.registries(), "Buffer is missing registries");
            final var registry = selector.select(registries);
            final int id = registry.getId(value);
            Check.stateCondition(id == -1, "Key {0} is not registered in registry {1}", value, registry.key());
            buffer.write(NetworkBuffer.VAR_INT, holder ? id + 1 : id);
        }

        @Override
        public RegistryKey<T> read(NetworkBuffer buffer) {
            final var registries = Objects.requireNonNull(buffer.registries(), "Buffer is missing registries");
            final var registry = selector.select(registries);
            final int raw = buffer.read(NetworkBuffer.VAR_INT);
            Check.stateCondition(holder && raw == 0, "Inline values are not supported for registry {0}", registry.key());
            final int id = holder ? raw - 1 : raw;
            final var key = registry.getKey(id);
            Check.stateCondition(key == null, "Unknown id {0} for registry {1}", id, registry.key());
            return key;
        }
    }

    record RegistryTagImpl<T>(Registries.Selector<T> selector) implements NetworkBuffer.Type<RegistryTag<T>> {
        @Override
        public void write(NetworkBuffer buffer, RegistryTag<T> value) {
            switch (value) {
                case Backed<T> backed -> {
                    buffer.write(NetworkBuffer.VAR_INT, 0);
                    buffer.write(NetworkBuffer.KEY, backed.key().key());
                }
                case Empty() -> buffer.write(NetworkBuffer.VAR_INT, 1);
                case Direct(var entries) -> {
                    final var registries = Objects.requireNonNull(buffer.registries(), "Buffer is missing registries");
                    final var registry = selector.select(registries);
                    buffer.write(NetworkBuffer.VAR_INT, entries.size() + 1);
                    for (RegistryKey<T> key : entries) {
                        final int id = registry.getId(key);
                        Check.stateCondition(id == -1, "Key {0} is not registered in registry {1}", key, registry.key());
                        buffer.write(NetworkBuffer.VAR_INT, id);
                    }
                }
            }
        }

        @Override
        public RegistryTag<T> read(NetworkBuffer buffer) {
            final var registries = Objects.requireNonNull(buffer.registries(), "Buffer is missing registries");
            final var registry = selector.select(registries);
            int count = buffer.read(NetworkBuffer.VAR_INT) - 1;
            if (count < 0) {
                final var key = buffer.read(NetworkBuffer.KEY);
                final var tag = registry.getTag(key);
                Check.stateCondition(tag == null, "No such tag {0} for registry {1}", key, registry.key());
                return tag;
            } else if (count == 0) {
                return RegistryTag.empty();
            } else {
                final List<RegistryKey<T>> keys = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    final int id = buffer.read(NetworkBuffer.VAR_INT);
                    final var key = registry.getKey(id);
                    Check.stateCondition(key == null, "Unknown id {0} for registry {1}", id, registry.key());
                    keys.add(key);
                }
                return new Direct<>(keys);
            }
        }
    }

}
