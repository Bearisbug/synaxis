package io.github.bearisbug.synaxis.app.moderation;

import io.github.bearisbug.synaxis.plugin.Ban;
import io.github.bearisbug.synaxis.plugin.BanTarget;
import org.jetbrains.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Bans and the whitelist in PostgreSQL ({@code docs/DESIGN-PROD.md} {@code API-PROD-004} V4,
 * {@code API-PROD-009}). Every method blocks, none of them runs on a tick thread.
 */
public final class ModerationStore {

    private final DataSource dataSource;

    /**
     * @param dataSource the database of the player store
     */
    public ModerationStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * The bans in force and the whitelist, as one shard keeps them in memory.
     *
     * @param bans      the bans neither lifted nor expired
     * @param whitelist the accounts on the whitelist
     */
    record Contents(List<Ban> bans, Set<UUID> whitelist) {
    }

    /**
     * Reads the bans in force and the whole whitelist.
     *
     * @return what was read
     * @throws SQLException if the database failed
     */
    Contents load() throws SQLException {
        final List<Ban> bans = new ArrayList<>();
        final Set<UUID> whitelist = new HashSet<>();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT ban_id, subject_uuid, subject_ip::text, reason, expires_at "
                    + "FROM ban WHERE revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())")) {
                while (rows.next()) {
                    final UUID uuid = rows.getObject(2, UUID.class);
                    final BanTarget target = uuid != null ? new BanTarget.ByUuid(uuid) : network(rows);
                    final OffsetDateTime expires = rows.getObject(5, OffsetDateTime.class);
                    bans.add(new Ban(rows.getLong(1), target, rows.getString(4),
                            expires == null ? null : expires.toInstant()));
                }
            }
            try (ResultSet rows = statement.executeQuery("SELECT account_uuid FROM whitelist")) {
                while (rows.next()) whitelist.add(rows.getObject(1, UUID.class));
            }
        }
        return new Contents(List.copyOf(bans), Set.copyOf(whitelist));
    }

    /**
     * The network of a ban row. The table takes networks a ban cannot hold, an IPv4 mapped IPv6 one written by hand
     * among them; such a row fails the read as the database failing would, naming the ban, so that a starting shard
     * refuses, a running one keeps what it read before and a change made here is put in by hand.
     */
    private static BanTarget network(ResultSet rows) throws SQLException {
        final String cidr = rows.getString(3);
        try {
            return new BanTarget.ByNetwork(cidr);
        } catch (IllegalArgumentException e) {
            throw new SQLException("ban " + rows.getLong(1) + " has subject_ip " + cidr
                    + ", which is not a network a ban can name: " + e.getMessage(), e);
        }
    }

    /**
     * Writes a ban.
     *
     * @param target    who it applies to
     * @param reason    the reason, already checked
     * @param expiresAt when it expires, null for never
     * @param operator  who bans
     * @return the ban with its id
     * @throws SQLException if the database failed
     */
    Ban ban(BanTarget target, String reason, @Nullable Instant expiresAt, UUID operator) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO ban (kind, subject_uuid, subject_ip, reason, created_by, expires_at) "
                             + "VALUES (?, ?, ?::cidr, ?, ?, ?) RETURNING ban_id")) {
            switch (target) {
                case BanTarget.ByUuid(UUID uuid) -> {
                    insert.setString(1, "uuid");
                    insert.setObject(2, uuid);
                    insert.setNull(3, Types.VARCHAR);
                }
                case BanTarget.ByNetwork(String cidr) -> {
                    insert.setString(1, "network");
                    insert.setNull(2, Types.OTHER);
                    insert.setString(3, cidr);
                }
            }
            insert.setString(4, reason);
            insert.setObject(5, operator);
            if (expiresAt == null) insert.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE);
            else insert.setObject(6, expiresAt.atOffset(ZoneOffset.UTC));
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return new Ban(row.getLong(1), target, reason, expiresAt);
            }
        }
    }

    /**
     * Lifts a ban in force.
     *
     * @param banId    the ban
     * @param operator who lifts it
     * @return the account or the network it applied to, empty if no such ban was in force
     * @throws SQLException if the database failed
     */
    Optional<String> revoke(long banId, UUID operator) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE ban SET revoked_at = now(), revoked_by = ? WHERE ban_id = ? AND revoked_at IS NULL "
                             + "AND (expires_at IS NULL OR expires_at > now()) "
                             + "RETURNING coalesce(subject_uuid::text, subject_ip::text)")) {
            update.setObject(1, operator);
            update.setLong(2, banId);
            try (ResultSet row = update.executeQuery()) {
                return row.next() ? Optional.of(row.getString(1)) : Optional.empty();
            }
        }
    }

    /**
     * Puts an account on the whitelist or takes it off; doing what is already done is no error.
     *
     * @param account the account
     * @param listed  true to put it on
     * @throws SQLException if the database failed
     */
    void whitelist(UUID account, boolean listed) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(listed
                     ? "INSERT INTO whitelist (account_uuid) VALUES (?) ON CONFLICT (account_uuid) DO NOTHING"
                     : "DELETE FROM whitelist WHERE account_uuid = ?")) {
            statement.setObject(1, account);
            statement.executeUpdate();
        }
    }
}
