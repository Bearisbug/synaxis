package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.instance.block.Block;

/**
 * A cell a world state flag governs ({@code docs/DESIGN-M3.md} {@code API-M3-013}).
 *
 * @param x     the block x
 * @param y     the block y
 * @param z     the block z
 * @param block the block it is to hold, with its block entity NBT and handler
 */
public record FlagCell(int x, int y, int z, Block block) {
}
