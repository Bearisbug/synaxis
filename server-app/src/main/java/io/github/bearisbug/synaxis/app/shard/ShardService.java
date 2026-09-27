package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.entity.Player;

import java.util.concurrent.CompletableFuture;

/**
 * Sends a player to another shard of the map ({@code docs/DESIGN-M3.md} {@code API-M3-010}): the
 * host interface the gates, {@code gm.transfer} and the management interface call. Only a shard in
 * the directory mode has one; it never reaches the plugin API.
 */
public interface ShardService {

    /** Returns the shard id of this shard, {@code synaxis.app.shard-id}. */
    String shardId();

    /** Returns the region of this shard. */
    String regionId();

    /**
     * Sends the player to the channel of a region chosen on the heartbeats ({@code API-M3-010}, the
     * choice of a channel). Callable from any thread, never blocks it.
     *
     * @param player   the player
     * @param regionId the target region
     * @return completes once the transfer packet is sent, or the transfer is known to have failed
     */
    CompletableFuture<ShardTransferResult> transfer(Player player, String regionId);

    /**
     * Sends the player to one channel: another channel of the same region, or where a GM sends it.
     * The target may be in this shard's region, but not this shard; it has to be live, accepting and
     * below its capacity. Otherwise as {@link #transfer}.
     *
     * @param player  the player
     * @param shardId the target shard
     * @return completes once the transfer packet is sent, or the transfer is known to have failed
     */
    CompletableFuture<ShardTransferResult> transferTo(Player player, String shardId);
}
