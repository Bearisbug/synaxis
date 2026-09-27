package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.instance.Instance;

/**
 * The gates of this shard's region ({@code docs/DESIGN-M3.md} {@code API-M3-010}, built into the host
 * as decided on 2026-09-28, §28.1): a player walking into the frame of a gate is sent to its target
 * region, once per walk in.
 * <p>
 * The M3-E step 0 contract: the signatures {@code Main} is assembled against. The transfer track
 * replaces the bodies.
 */
@SuppressWarnings("DoNotCallSuggester") // the bodies of the skeleton, replaced by the transfer track
public final class ShardGates {

    /**
     * @param directory the shard directory, whose region of this shard has the gates
     * @param service   what sends a player to the target region
     */
    public ShardGates(ShardDirectory directory, ShardService service) {
        throw new UnsupportedOperationException("ShardGates is the M3-E step 0 skeleton");
    }

    /**
     * Listens to {@code PlayerMoveEvent} on the map instance.
     *
     * @param instance the map instance
     */
    public void attach(Instance instance) {
        throw new UnsupportedOperationException("ShardGates is the M3-E step 0 skeleton");
    }
}
