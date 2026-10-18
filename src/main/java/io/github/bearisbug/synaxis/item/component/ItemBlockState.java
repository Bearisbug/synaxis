package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.NetworkBuffer;

import java.util.HashMap;
import java.util.Map;

public record ItemBlockState(Map<String, String> properties) {
    public static final ItemBlockState EMPTY = new ItemBlockState(Map.of());

    public static final NetworkBuffer.Type<ItemBlockState> NETWORK_TYPE = NetworkBuffer.STRING.mapValue(NetworkBuffer.STRING)
            .transform(ItemBlockState::new, ItemBlockState::properties);
    public static final Codec<ItemBlockState> CODEC = Codec.STRING.mapValue(Codec.STRING)
            .transform(ItemBlockState::new, ItemBlockState::properties);

    public ItemBlockState {
        properties = Map.copyOf(properties);
    }

    public ItemBlockState(String key, String value) {
        this(Map.of(key, value));
    }

    public ItemBlockState with(String key, String value) {
        Map<String, String> newProperties = new HashMap<>(properties);
        newProperties.put(key, value);
        return new ItemBlockState(newProperties);
    }

    /**
     * Applies these properties to {@code block}, the way the client predicts a placement: a property the block does
     * not have, or a value the property does not take, is skipped.
     *
     * @param block the block to apply the properties to
     * @return {@code block} with the applicable properties set
     */
    public Block apply(Block block) {
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (block.getProperty(entry.getKey()) == null)
                continue; // Ignore properties not present on this block
            if (!takesValue(block, entry.getKey(), entry.getValue()))
                continue; // Ignore values the property does not take
            block = block.withProperty(entry.getKey(), entry.getValue());
        }
        return block;
    }

    private static boolean takesValue(Block block, String property, String value) {
        for (Block state : block.possibleStates()) {
            if (value.equals(state.getProperty(property))) return true;
        }
        return false;
    }
}
