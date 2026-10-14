package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.instance.block.Block;

import java.util.Objects;

/**
 * A cell a world state flag governs ({@code docs/DESIGN-M3.md} {@code API-M3-013}). A cell holds its
 * block when {@link io.github.bearisbug.synaxis.synx.SynxChunk#sameBlock} says so: the NBT keys
 * {@code id}, {@code x}, {@code y}, {@code z} and {@code keepPacked} are not kept, and a handler counts
 * by its namespace.
 *
 * @param x     the block x
 * @param y     the block y
 * @param z     the block z
 * @param block the block it is to hold, with its block entity NBT and handler; not null
 */
public record FlagCell(int x, int y, int z, Block block) {

    /**
     * Refuses a null block ({@code Block.fromKey} gives one for an unknown name), so that the applier
     * that builds the cell throws, and its flag fails the way a throwing applier does.
     */
    public FlagCell {
        Objects.requireNonNull(block, "block");
    }
}
