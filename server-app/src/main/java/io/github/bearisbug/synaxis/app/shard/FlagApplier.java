package io.github.bearisbug.synaxis.app.shard;

import com.google.gson.JsonElement;

import java.util.List;

/**
 * How a world state flag lands on the blocks ({@code docs/DESIGN-M3.md} {@code API-M3-013}).
 */
@FunctionalInterface
public interface FlagApplier {

    /**
     * Every cell a value asks for. Called on any thread, possibly more than once for one value and for
     * values that are then dropped, so it only computes the cells. The values of one key should give the
     * same set of coordinates: a cell only an earlier value gave keeps its block and is no longer
     * protected, and a cell new to a value counts no conflict. Two keys never give the same cell: the
     * key applied last takes it, with a warning.
     *
     * @param value the value of the flag
     * @return the cells and the block each one is to hold
     */
    List<FlagCell> cells(JsonElement value);
}
