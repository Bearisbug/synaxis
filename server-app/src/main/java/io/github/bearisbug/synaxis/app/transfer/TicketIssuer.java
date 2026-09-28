package io.github.bearisbug.synaxis.app.transfer;

import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.entity.Player;
import net.kyori.adventure.text.Component;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * The source side of a transfer ({@code docs/DESIGN-PROD.md} {@code API-PROD-028} issuing ① to ④):
 * cooldown, the write that gives the lease back, then the ticket stored on the client before the
 * client is told where to go. Every ticket sent is one {@code EVT:transfer_issued}
 * ({@code docs/DESIGN-M3.md} {@code API-M3-010}), written here because only here are its
 * {@code transfer_ref} and {@code issued_at} known.
 * <p>
 * The write completes on a {@code Syn-Store-Write} thread; ④ runs at the player's next tick, on the
 * tick thread that owns it: {@code OutgoingTransferEvent} is a player event, and its listeners may
 * touch the player ({@code API-PROD-016}). A player that left before that tick gets no ticket; one
 * still connected gets it at its tick, however late.
 */
public final class TicketIssuer implements TransferTickets {

    /** What the player sees when its character could not be written for the transfer. */
    static final String TRANSFER_FAILED = "synaxis:transfer-failed";

    private final PlayerStore store;
    private final TransferKeys keys;
    private final TransferCooldowns cooldowns;
    private final String shardId;
    private final BiConsumer<String, Map<String, ?>> analytics;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param store     the player store of this shard
     * @param keys      the keys, the first one signs
     * @param cooldowns the cooldowns, shared with this shard's {@link TicketVerifier}
     * @param shardId   the id of this shard, the ticket's {@code source_shard}
     * @param analytics the event ledger, for {@code EVT:transfer_issued} ({@code docs/DESIGN-M3.md}
     *                  {@code API-M3-010})
     */
    public TicketIssuer(PlayerStore store, TransferKeys keys, TransferCooldowns cooldowns, String shardId,
                        BiConsumer<String, Map<String, ?>> analytics) {
        this.store = store;
        this.keys = keys;
        this.cooldowns = cooldowns;
        this.shardId = shardId;
        this.analytics = analytics;
    }

    @Override
    public CompletableFuture<TransferOutcome> transfer(Player player, String targetShardId, String host, int port) {
        if (cooldowns.coolingDown(player.getUuid())) return CompletableFuture.completedFuture(TransferOutcome.COOLDOWN);
        final CompletableFuture<PlayerStore.Handoff> handoff = store.transferOut(player);
        if (handoff == null) return CompletableFuture.completedFuture(TransferOutcome.NOT_ONLINE);
        return handoff.thenCompose(released -> {
            if (released == null) {
                player.sendMessage(Component.translatable(TRANSFER_FAILED));
                return CompletableFuture.completedFuture(TransferOutcome.SAVE_FAILED);
            }
            final CompletableFuture<TransferOutcome> sent = new CompletableFuture<>();
            // Whichever of the tick and the check for a player gone comes first decides, the other one does nothing
            final AtomicBoolean decided = new AtomicBoolean();
            player.scheduler().scheduleNextTick(() -> {
                if (!decided.compareAndSet(false, true)) return;
                try {
                    sent.complete(send(player, released, targetShardId, host, port));
                } catch (Throwable throwable) {
                    sent.completeExceptionally(throwable);
                }
            });
            decideOnceGone(player, decided, sent);
            return sent;
        });
    }

    /**
     * A player removed before its next tick never runs ④, so every
     * {@link PluginTransfers#TICK_TIMEOUT_MILLIS} the connection is looked at until one of the two
     * decides. A player still connected is waited for however late its tick: the lease is given back
     * already, and a player given up on would stay in {@code in_transit} without a ticket.
     */
    private static void decideOnceGone(Player player, AtomicBoolean decided, CompletableFuture<TransferOutcome> sent) {
        CompletableFuture.delayedExecutor(PluginTransfers.TICK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS, Runnable::run).execute(() -> {
            if (decided.get()) return;
            if (player.isOnline()) decideOnceGone(player, decided, sent);
            else if (decided.compareAndSet(false, true)) sent.complete(TransferOutcome.NOT_ONLINE);
        });
    }

    /** ④ on the tick thread that owns the player. */
    private TransferOutcome send(Player player, PlayerStore.Handoff released, String targetShardId, String host, int port) {
        // Gone since the write: nobody would take the ticket
        if (!player.isOnline()) return TransferOutcome.NOT_ONLINE;
        final byte[] nonce = new byte[TransferTicket.NONCE_BYTES];
        random.nextBytes(nonce);
        final long issuedAt = cooldowns.now();
        final TransferTicket ticket = new TransferTicket(keys.signingId(), released.account(), released.characterId(),
                shardId, targetShardId, released.version(), nonce, issuedAt, issuedAt + TransferTicket.LIFETIME_MS);
        cooldowns.record(released.account(), issuedAt);
        // The cookie first: the client stores it before it acts on the transfer
        player.getPlayerConnection().storeCookie(TransferTicket.COOKIE, ticket.encode(keys));
        player.getPlayerConnection().transfer(host, port);
        final Map<String, Object> issued = new LinkedHashMap<>();
        issued.put("transfer_ref", ticket.reference());
        issued.put("player_uuid", ticket.playerUuid());
        issued.put("source_shard", shardId);
        issued.put("target_shard", targetShardId);
        issued.put("issued_at", issuedAt);
        analytics.accept("transfer_issued", issued);
        return TransferOutcome.SENT;
    }
}
