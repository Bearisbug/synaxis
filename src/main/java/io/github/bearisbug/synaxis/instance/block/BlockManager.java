package io.github.bearisbug.synaxis.instance.block;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.instance.block.rule.BlockPlacementRule;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.vectrix.flare.fastutil.Int2ObjectSyncMap;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The block handlers, by handler key, and the block placement rules, by block type, of a server process.
 */
public final class BlockManager {
    private final static Logger LOGGER = LoggerFactory.getLogger(BlockManager.class);
    // handler key (e.g. minecraft:chest) -> handler supplier
    private final Map<String, Supplier<? extends BlockHandler>> blockHandlerMap = new ConcurrentHashMap<>();
    // block id -> block placement rule
    private final Int2ObjectMap<BlockPlacementRule> placementRuleMap = Int2ObjectSyncMap.hashmap();

    private final Set<String> dummyWarning = ConcurrentHashMap.newKeySet(); // Prevent warning spam

    /**
     * Registers the handler of a key. A key registered before gets the new supplier, with a WARN.
     * <p>
     * {@link #getHandler(String)} calls the supplier each time: pass {@code () -> INSTANCE} for a single instance.
     * The {@link BlockHandler#getKey()} of the handlers it makes is expected to be {@code key}.
     *
     * @param key             the handler key in full, as {@link Key#asString()} writes it ({@code minecraft:chest}); it
     *                        is not normalized, so {@code chest} is another key
     * @param handlerSupplier makes the handler
     */
    public void registerHandler(String key, Supplier<? extends BlockHandler> handlerSupplier) {
        if (blockHandlerMap.put(key, handlerSupplier) != null) {
            LOGGER.warn("Block handler {} replaced by a later registration", key);
        }
    }

    /**
     * Registers the handler of a key, see {@link #registerHandler(String, Supplier)}.
     *
     * @param key             the handler key
     * @param handlerSupplier makes the handler
     */
    public void registerHandler(Key key, Supplier<? extends BlockHandler> handlerSupplier) {
        registerHandler(key.toString(), handlerSupplier);
    }

    /**
     * Makes the handler registered for a key.
     *
     * @param key the handler key in full, as {@link Key#asString()} writes it; it is not normalized
     * @return a handler from the supplier of the key, null if the key has none
     */
    public @Nullable BlockHandler getHandler(String key) {
        final var handler = blockHandlerMap.get(key);
        return handler != null ? handler.get() : null;
    }

    @ApiStatus.Internal
    public BlockHandler getHandlerOrDummy(String key) {
        BlockHandler handler = getHandler(key);
        if (handler == null) {
            if (dummyWarning.add(key)) {
                LOGGER.warn("No block handler registered for {}; using a placeholder that keeps the key and NBT but has "
                        + "no behavior. Register one with BlockManager#registerHandler or a META-INF/services "
                        + "BlockHandler entry.", key);
            }
            handler = BlockHandler.Dummy.get(key);
        }
        return handler;
    }

    /**
     * Registers a {@link BlockPlacementRule} for the block type of its {@link BlockPlacementRule#getBlock()}. A block
     * type has one rule: a type registered before gets the new rule, with a WARN.
     *
     * @param blockPlacementRule the block placement rule to register
     */
    public void registerBlockPlacementRule(BlockPlacementRule blockPlacementRule) {
        final Block block = blockPlacementRule.getBlock();
        final BlockPlacementRule previous = placementRuleMap.put(block.id(), blockPlacementRule);
        if (previous != null) {
            LOGGER.warn("Block placement rule of {} replaced by a later registration: {} by {}", block.key().asString(),
                    previous.getClass().getName(), blockPlacementRule.getClass().getName());
        }
    }

    /**
     * Gets the {@link BlockPlacementRule} of the specific block.
     *
     * @param block the block to check
     * @return the block placement rule associated with the block, null if not any
     */
    public @Nullable BlockPlacementRule getBlockPlacementRule(Block block) {
        return placementRuleMap.get(block.id());
    }
}
