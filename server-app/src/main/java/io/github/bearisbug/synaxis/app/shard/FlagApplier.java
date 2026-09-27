package io.github.bearisbug.synaxis.app.shard;

import com.google.gson.JsonElement;

import java.util.List;

/**
 * How a world state flag lands on the blocks ({@code docs/DESIGN-M3.md} {@code API-M3-013}).
 */
@FunctionalInterface
public interface FlagApplier {

    /**
     * Every cell a value asks for. The values of one key should give the same set of coordinates.
     *
     * @param value the value of the flag
     * @return the cells and the block each one is to hold
     */
    List<FlagCell> cells(JsonElement value);
}
