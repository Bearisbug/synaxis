package io.github.bearisbug.synaxis.app.shard;

import com.google.gson.JsonElement;

/**
 * A value of a world state flag and its version ({@code docs/DESIGN-M3.md} {@code API-M3-013}).
 *
 * @param value   the value
 * @param version its version, from 1 up
 */
public record FlagValue(JsonElement value, long version) {
}
