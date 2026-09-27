package io.github.bearisbug.synaxis.app.shard;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Reads {@code shard_heartbeat} ({@code docs/DESIGN-M3.md} {@code API-M3-010}), what
 * {@link ShardService} chooses a channel on. Both methods run on a virtual thread with a statement
 * timeout of 2 s and complete exceptionally when the read fails. Both filter on {@code map_id}: two
 * maps may share one database.
 */
public interface HeartbeatReader {

    /**
     * Every row of a region of this map, the expired ones included ({@code live} false).
     *
     * @param mapId    the map
     * @param regionId the region
     * @return completes with the rows
     */
    CompletableFuture<List<HeartbeatRow>> region(String mapId, String regionId);

    /**
     * The one row of a shard of this map.
     *
     * @param mapId   the map
     * @param shardId the shard
     * @return completes with the row, or null when there is none, or it belongs to another map
     */
    CompletableFuture<@Nullable HeartbeatRow> shard(String mapId, String shardId);
}
