package io.github.bearisbug.synaxis.app.transfer;

import io.github.bearisbug.synaxis.app.store.PostgresBackend;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * The target side of a transfer ({@code docs/DESIGN-PROD.md} {@code API-PROD-028} checks ② to ⑥,
 * §11.3 {@code in_transit → loading | offline}): on the configuration thread of a connection that
 * came in with the transfer intent. {@link #read} reads the ticket before the load; {@link #claim}
 * is what the load runs in the account's turn, right before its acquire (§11.3 end). The first
 * check that fails decides:
 * <ol start="2">
 * <li>the cookie, its layout, key and signature ({@code bad-signature});</li>
 * <li>its expiry ({@code expired});</li>
 * <li>this shard and the account verified for this login ({@code wrong-target},
 * {@code uuid-mismatch});</li>
 * <li>the nonce, kept once in {@code transfer_nonce} and never rolled back ({@code nonce-reused});</li>
 * <li>the lease of the character ({@code lease-held}), then the version it was released at
 * ({@code version-mismatch}, a consistency assertion).</li>
 * </ol>
 * Check ①, the capacity of the shard, is the connection gate's L3 and happened at login start.
 */
public final class TicketVerifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(TicketVerifier.class);
    /** How long the client gets to hand the cookie back, then it counts as missing. */
    static final long COOKIE_TIMEOUT_MILLIS = 5_000;

    private static final String INSERT_NONCE = """
            INSERT INTO transfer_nonce (nonce, expires_at) VALUES (?, to_timestamp(? / 1000.0))
            ON CONFLICT DO NOTHING""";
    // The acquire statement of API-PROD-004 for one character, which has to belong to the account
    private static final String ACQUIRE = """
            UPDATE character c SET lease_session_id = ?
             WHERE c.character_id = ? AND c.account_uuid = ?"""
            + " AND " + PostgresBackend.LEASE_AVAILABLE + " RETURNING c.version";
    private static final String RELEASE = """
            UPDATE character SET lease_session_id = NULL WHERE character_id = ? AND lease_session_id = ?""";

    /** Why a ticket is refused, the {@code reject_reason} of §9. */
    enum Reason {
        BAD_SIGNATURE("bad-signature", DisconnectKeys.TRANSFER_REJECTED),
        EXPIRED("expired", DisconnectKeys.TRANSFER_REJECTED),
        WRONG_TARGET("wrong-target", DisconnectKeys.TRANSFER_REJECTED),
        UUID_MISMATCH("uuid-mismatch", DisconnectKeys.TRANSFER_REJECTED),
        NONCE_REUSED("nonce-reused", DisconnectKeys.TRANSFER_REJECTED),
        LEASE_HELD("lease-held", DisconnectKeys.STATE_UNAVAILABLE),
        STORE_UNAVAILABLE("store-unavailable", DisconnectKeys.STATE_UNAVAILABLE),
        VERSION_MISMATCH("version-mismatch", DisconnectKeys.TRANSFER_REJECTED);

        final String value;
        final String disconnectKey;

        Reason(String value, String disconnectKey) {
            this.value = value;
            this.disconnectKey = disconnectKey;
        }
    }

    private final LogBudget failures = new LogBudget(LOGGER, "transfer tickets the store could not check");
    private final DataSource dataSource;
    private final LongSupplier sessionId;
    private final String shardId;
    private final TransferKeys keys;
    private final TransferCooldowns cooldowns;
    private final BiConsumer<String, Map<String, ?>> analytics;
    private final BiConsumer<Player, TransferTicket> onClaimed;

    /**
     * @param dataSource the player store database
     * @param sessionId  the current session of this shard
     * @param shardId    the id of this shard, the only {@code target_shard} accepted
     * @param keys       the keys a ticket may be signed with
     * @param cooldowns  the cooldowns, shared with this shard's {@link TicketIssuer}
     * @param analytics  the event ledger
     * @param onClaimed  told about every ticket {@link #claim} accepts ({@code docs/DESIGN-M3.md}
     *                   {@code API-M3-010}, the arrival on a shard in the directory mode)
     */
    public TicketVerifier(DataSource dataSource, LongSupplier sessionId, String shardId, TransferKeys keys,
                          TransferCooldowns cooldowns, BiConsumer<String, Map<String, ?>> analytics,
                          BiConsumer<Player, TransferTicket> onClaimed) {
        this.dataSource = dataSource;
        this.sessionId = sessionId;
        this.shardId = shardId;
        this.keys = keys;
        this.cooldowns = cooldowns;
        this.analytics = analytics;
        this.onClaimed = onClaimed;
    }

    /**
     * Reads the ticket of a connection that came in with the transfer intent and checks what needs
     * no database, ② to ④. A refused one is kicked.
     *
     * @param player the player of the connection, in the configuration phase
     * @return the ticket, or null if it is refused
     */
    public @Nullable TransferTicket read(Player player) {
        final TransferTicket ticket = TransferTicket.decode(cookie(player), keys);
        if (ticket == null) return rejected(player, null, Reason.BAD_SIGNATURE);
        if (cooldowns.now() > ticket.expiresAt()) return rejected(player, ticket, Reason.EXPIRED);
        if (!shardId.equals(ticket.targetShard())) return rejected(player, ticket, Reason.WRONG_TARGET);
        if (!player.getUuid().equals(ticket.playerUuid())) return rejected(player, ticket, Reason.UUID_MISMATCH);
        return ticket;
    }

    /**
     * Uses a ticket {@link #read} accepted, ⑤ and ⑥: spends its nonce and takes the character's
     * lease at the version it was released at. Run by the load, in the account's turn
     * ({@code PlayerStore#load(Player, BooleanSupplier)}). A refused one is kicked; an accepted one
     * holds the lease and goes on to the ordinary load. An accepted one is told to
     * {@code onClaimed} last, once its cooldown is recorded.
     *
     * @param player the player of the connection
     * @param ticket its ticket
     * @return true if the ticket is accepted
     */
    public boolean claim(Player player, TransferTicket ticket) {
        final long session = sessionId.getAsLong();
        try (Connection connection = dataSource.getConnection()) {
            // Committed on its own: whatever fails after this, the ticket has been used
            try (PreparedStatement insert = connection.prepareStatement(INSERT_NONCE)) {
                insert.setBytes(1, ticket.nonce());
                insert.setLong(2, ticket.expiresAt());
                if (insert.executeUpdate() == 0) return reject(player, ticket, Reason.NONCE_REUSED);
            }
            final long version;
            try (PreparedStatement acquire = connection.prepareStatement(ACQUIRE)) {
                acquire.setLong(1, session);
                acquire.setLong(2, ticket.characterId());
                acquire.setObject(3, ticket.playerUuid());
                acquire.setLong(4, session);
                try (ResultSet row = acquire.executeQuery()) {
                    if (!row.next()) return reject(player, ticket, Reason.LEASE_HELD);
                    version = row.getLong(1);
                }
            }
            if (version != ticket.stateVersion()) {
                LOGGER.error("A transfer ticket names a version the character is not at: transfer_ref={} character_id={} "
                        + "state_version={} version={}. Giving the lease back and refusing the transfer.",
                        ticket.reference(), ticket.characterId(), ticket.stateVersion(), version);
                try (PreparedStatement release = connection.prepareStatement(RELEASE)) {
                    release.setLong(1, ticket.characterId());
                    release.setLong(2, session);
                    release.executeUpdate();
                }
                return reject(player, ticket, Reason.VERSION_MISMATCH);
            }
        } catch (SQLException e) {
            failures.warn(e, "A transfer ticket could not be checked against the store: transfer_ref={}", ticket.reference());
            return reject(player, ticket, Reason.STORE_UNAVAILABLE);
        }
        cooldowns.record(ticket.playerUuid(), ticket.issuedAt());
        onClaimed.accept(player, ticket);
        return true;
    }

    private static byte @Nullable [] cookie(Player player) {
        try {
            return player.getPlayerConnection().fetchCookie(TransferTicket.COOKIE)
                    .get(COOKIE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException _) {
            return null;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private @Nullable TransferTicket rejected(Player player, @Nullable TransferTicket ticket, Reason reason) {
        reject(player, ticket, reason);
        return null;
    }

    private boolean reject(Player player, @Nullable TransferTicket ticket, Reason reason) {
        LOGGER.info("Refusing a transfer: connection_id={} transfer_ref={} reason={}",
                player.getPlayerConnection().connectionId(), ticket == null ? null : ticket.reference(), reason.value);
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("transfer_ref", ticket == null ? null : ticket.reference());
        attributes.put("reason", reason.value);
        attributes.put("source_shard", ticket == null ? null : ticket.sourceShard());
        attributes.put("target_shard", ticket == null ? null : ticket.targetShard());
        attributes.put("player_uuid", player.getUuid());
        analytics.accept("transfer_rejected", attributes);
        player.kick(Component.translatable(reason.disconnectKey));
        return false;
    }
}
