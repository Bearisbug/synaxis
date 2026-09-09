package dev.gameengine.item.component;

import dev.gameengine.codec.Codec;
import dev.gameengine.instance.block.Block;
import dev.gameengine.instance.block.predicate.BlockPredicate;
import dev.gameengine.network.NetworkBuffer;

import java.util.List;
import java.util.function.Predicate;

public record BlockPredicates(List<BlockPredicate> predicates) implements Predicate<Block> {
    /**
     * Will never match any block.
     */
    public static final BlockPredicates NEVER = new BlockPredicates(List.of());

    public static final NetworkBuffer.Type<BlockPredicates> NETWORK_TYPE = BlockPredicate.NETWORK_TYPE.list(Short.MAX_VALUE)
            .transform(BlockPredicates::new, BlockPredicates::predicates);
    public static final Codec<BlockPredicates> CODEC = BlockPredicate.CODEC.listOrSingle(Short.MAX_VALUE)
            .transform(BlockPredicates::new, BlockPredicates::predicates);

    public BlockPredicates {
        predicates = List.copyOf(predicates);
    }

    public BlockPredicates(BlockPredicate predicate) {
        this(List.of(predicate));
    }

    @Override
    public boolean test(Block block) {
        for (BlockPredicate predicate : predicates) {
            if (predicate.test(block)) {
                return true;
            }
        }
        return false;
    }
}
