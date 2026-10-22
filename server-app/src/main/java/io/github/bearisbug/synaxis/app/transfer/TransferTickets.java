package io.github.bearisbug.synaxis.app.transfer;

import io.github.bearisbug.synaxis.entity.Player;

import java.util.concurrent.CompletableFuture;

/**
 * Sends a player to another shard with a ticket ({@code docs/DESIGN-PROD.md} {@code API-PROD-028}).
 * The host interface {@code ShardService#transfer} calls once it picked the channel; it never reaches
 * the plugin API.
 */
public interface TransferTickets {

    /**
     * Writes the player's character and gives its lease back, then stores the ticket on the client
     * and sends the client to the target. Callable from any thread.
     *
     * @param player        the player
     * @param targetShardId the shard the ticket is valid for
     * @param host          where the client connects
     * @param port          the port it connects to
     * @return completes once the transfer packet is sent or the transfer is known to have failed, with the
     *         outcome of {@link TransferOutcome}
     */
    CompletableFuture<TransferOutcome> transfer(Player player, String targetShardId, String host, int port);
}
