package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.synx.SynxManifest;

/**
 * Whether a process puts a returning player back where {@code synaxis:player} saved it
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-032}), one rule per mode.
 */
public sealed interface PositionRule {

    /** The flat core world: a position saved in the core world. */
    record Core() implements PositionRule {
    }

    /** A gameplay plugin: never, the plugin's spawn decides. */
    record Rts() implements PositionRule {
    }

    /**
     * A map shard: a position saved on the same map release, inside the chunks this shard serves, by a player that
     * did not arrive with a transfer ticket.
     *
     * @param mapId   the map id of the shard's map release
     * @param buildId the build id of the shard's map release
     * @param bounds  the chunks the shard serves
     */
    record MapShard(String mapId, String buildId, SynxManifest.Bounds bounds) implements PositionRule {
    }
}
