package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.block.BlockManager;
import net.kyori.adventure.key.Key;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * The handlers a data marker may name (API-M3-003): the {@link BlockHandler} services on the class path.
 * The baker and a shard both register them through {@link #registerAll(BlockManager)}, because segment ①
 * carries only the client tags the handler of a block entity declares: the two must see the same list.
 */
public final class SynxHandlers {
    private SynxHandlers() {
    }

    /**
     * Registers every {@link BlockHandler} service of the class path under its {@link BlockHandler#getKey()},
     * each as one instance.
     *
     * @return the registered keys
     * @throws IllegalArgumentException when two services have the same key, naming the key and both
     *                                  classes; then nothing is registered
     */
    public static Set<Key> registerAll(BlockManager blocks) {
        return registerAll(blocks, ServiceLoader.load(BlockHandler.class));
    }

    static Set<Key> registerAll(BlockManager blocks, Iterable<? extends BlockHandler> handlers) {
        final Map<Key, BlockHandler> byKey = new LinkedHashMap<>();
        for (BlockHandler handler : handlers) {
            final BlockHandler first = byKey.putIfAbsent(handler.getKey(), handler);
            if (first != null) {
                throw new IllegalArgumentException("two block handlers have the key " + handler.getKey().asString()
                        + ": " + first.getClass().getName() + " and " + handler.getClass().getName());
            }
        }
        byKey.forEach((key, handler) -> blocks.registerHandler(key, () -> handler));
        return Collections.unmodifiableSet(byKey.keySet());
    }
}
