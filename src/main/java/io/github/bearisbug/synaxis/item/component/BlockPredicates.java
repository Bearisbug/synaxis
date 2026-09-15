package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.predicate.BlockPredicate;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.Registries;

import java.util.List;

public record BlockPredicates(List<BlockPredicate> predicates) {
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

    public boolean test(Registries registries, Block block) {
        for (BlockPredicate predicate : predicates) {
            if (predicate.test(registries, block)) {
                return true;
            }
        }
        return false;
    }
}
