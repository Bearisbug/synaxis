package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.app.transfer.TransferTicket;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * The arrivals of transferred players on this shard ({@code docs/DESIGN-M3.md} {@code API-M3-010}):
 * a player whose ticket was claimed spawns at the arrival of the gate leading back to where it came
 * from, and {@code EVT:transfer_completed} is written once its first 9 chunks are sent.
 * <p>
 * The M3-E step 0 contract: the signatures {@code Main} is assembled against. The transfer track
 * replaces the bodies.
 */
@SuppressWarnings("DoNotCallSuggester") // the bodies of the skeleton, replaced by the transfer track
public final class ShardArrivals {

    /**
     * @param directory the shard directory of this shard
     * @param events    where the events go, the event ledger of the host
     * @param clock     the wall clock in milliseconds, what a ticket's {@code issued_at} is on
     */
    public ShardArrivals(ShardDirectory directory, BiConsumer<String, Map<String, ?>> events, LongSupplier clock) {
        throw new UnsupportedOperationException("ShardArrivals is the M3-E step 0 skeleton");
    }

    /**
     * What {@code TicketVerifier#claim} calls for a ticket it accepted: on the configuration thread,
     * within the account's turn of the load, in memory only.
     *
     * @param player the player of the connection
     * @param ticket its ticket
     */
    public void claimed(Player player, TransferTicket ticket) {
        throw new UnsupportedOperationException("ShardArrivals is the M3-E step 0 skeleton");
    }

    /**
     * Listens to {@code PlayerChunkLoadEvent} and {@code PlayerDisconnectEvent} on the map instance.
     *
     * @param instance the map instance
     */
    public void attach(Instance instance) {
        throw new UnsupportedOperationException("ShardArrivals is the M3-E step 0 skeleton");
    }
}
