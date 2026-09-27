package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.app.transfer.TransferTicket;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerChunkLoadEvent;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.instance.Instance;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * The arrivals of transferred players on this shard ({@code docs/DESIGN-M3.md} {@code API-M3-010}):
 * a player whose ticket was claimed spawns at the arrival of the gate leading back to where it came
 * from, and {@code EVT:transfer_completed} is written once its first 9 chunks are sent.
 * <p>
 * Without a gate back to the source region the player keeps the spawn of the region. An arrival
 * whose player leaves before its 9th chunk writes nothing, and one still short of it 60 s after its
 * claim is dropped at the next claim: both count as transfers that did not complete. A player that
 * leaves before it is in the map instance, still in the configuration phase, is not seen leaving by
 * the instance's node: its arrival, and the player it holds, go with the 60 s rule.
 */
public final class ShardArrivals {

    /** The chunks of the first batch, the 9 a vanilla client waits for. */
    private static final int FIRST_BATCH = 9;
    /** How long an arrival waits for its first batch before it is dropped. */
    private static final long FORGET_AFTER_MILLIS = 60_000;

    /** An arrival waiting for its first batch; its chunks are counted on the player's tick thread. */
    private record Arrival(String transferRef, long issuedAt, String sourceShard, long claimedAt, AtomicInteger chunks) {
    }

    private final ShardDirectory directory;
    private final BiConsumer<String, Map<String, ?>> events;
    private final LongSupplier clock;
    private final Map<Player, Arrival> arrivals = new ConcurrentHashMap<>();

    /**
     * @param directory the shard directory of this shard
     * @param events    where the events go, the event ledger of the host
     * @param clock     the wall clock in milliseconds, what a ticket's {@code issued_at} is on
     */
    public ShardArrivals(ShardDirectory directory, BiConsumer<String, Map<String, ?>> events, LongSupplier clock) {
        this.directory = directory;
        this.events = events;
        this.clock = clock;
    }

    /**
     * What {@code TicketVerifier#claim} calls for a ticket it accepted: on the configuration thread,
     * within the account's turn of the load, in memory only.
     *
     * @param player the player of the connection
     * @param ticket its ticket
     */
    public void claimed(Player player, TransferTicket ticket) {
        final String sourceRegion = ShardDirectory.regionOfShard(ticket.sourceShard());
        final ShardDirectory.Gate back = sourceRegion == null ? null : directory.gateTo(sourceRegion);
        // The spawn serveMap set on the root node came first, this one wins
        if (back != null) player.setRespawnPoint(back.arrival());
        final long now = clock.getAsLong();
        arrivals.values().removeIf(arrival -> now - arrival.claimedAt() >= FORGET_AFTER_MILLIS);
        arrivals.put(player, new Arrival(ticket.reference(), ticket.issuedAt(), ticket.sourceShard(), now,
                new AtomicInteger()));
    }

    /**
     * Listens to {@code PlayerChunkLoadEvent} and {@code PlayerDisconnectEvent} on the map instance.
     *
     * @param instance the map instance
     */
    public void attach(Instance instance) {
        instance.eventNode().addListener(PlayerChunkLoadEvent.class, event -> chunkSent(event.getPlayer()))
                .addListener(PlayerDisconnectEvent.class, event -> arrivals.remove(event.getPlayer()));
    }

    private void chunkSent(Player player) {
        final Arrival arrival = arrivals.get(player);
        if (arrival == null || arrival.chunks().incrementAndGet() != FIRST_BATCH || !arrivals.remove(player, arrival)) return;
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("transfer_ref", arrival.transferRef());
        attributes.put("first_batch_ms", clock.getAsLong() - arrival.issuedAt());
        attributes.put("source_shard", arrival.sourceShard());
        attributes.put("target_shard", directory.shardId());
        events.accept("transfer_completed", attributes);
    }
}
