package io.github.bearisbug.synaxis.app.permission;

import org.jetbrains.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The roles of the accounts in PostgreSQL ({@code docs/DESIGN-PROD.md} {@code API-PROD-004}
 * V3, {@code API-PROD-010}): {@code account_role} says who holds which role, {@code role_permission}
 * which nodes a role grants. Every method blocks, none of them runs on a tick thread.
 */
public final class RoleStore {

    /** The SQLSTATE of a foreign key violation: the role does not exist. */
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private final DataSource dataSource;

    /**
     * @param dataSource the database of the player store
     */
    public RoleStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * What an account's roles grant.
     *
     * @param roles     the role ids
     * @param nodes     the nodes they grant, wildcards included
     * @param expiresAt the earliest expiry among these grants, null if none of them expires
     */
    record Grants(Set<String> roles, Set<String> nodes, @Nullable Instant expiresAt) {
        static final Grants NONE = new Grants(Set.of(), Set.of(), null);
    }

    /** How a grant or a revocation ended. */
    enum Change { DONE, UNKNOWN_ROLE, NOT_GRANTED }

    /**
     * Reads the roles of accounts that have not expired, and the nodes they grant, in one query.
     *
     * @param accounts the accounts
     * @return what each account holds; an account without any role is absent
     * @throws SQLException if the database failed
     */
    Map<UUID, Grants> load(Collection<UUID> accounts) throws SQLException {
        final Map<UUID, Set<String>> roles = new HashMap<>();
        final Map<UUID, Set<String>> nodes = new HashMap<>();
        final Map<UUID, Instant> expiries = new HashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT ar.account_uuid, ar.role_id, rp.node, ar.expires_at FROM account_role ar "
                             + "LEFT JOIN role_permission rp ON rp.role_id = ar.role_id "
                             + "WHERE ar.account_uuid = ANY (?) AND (ar.expires_at IS NULL OR ar.expires_at > now())")) {
            final Array array = connection.createArrayOf("uuid", accounts.toArray());
            select.setArray(1, array);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    final UUID account = rows.getObject(1, UUID.class);
                    roles.computeIfAbsent(account, _ -> new TreeSet<>()).add(rows.getString(2));
                    final String node = rows.getString(3);
                    final Set<String> granted = nodes.computeIfAbsent(account, _ -> new TreeSet<>());
                    if (node != null) granted.add(node);
                    final OffsetDateTime expires = rows.getObject(4, OffsetDateTime.class);
                    if (expires != null) expiries.merge(account, expires.toInstant(), (a, b) -> a.isBefore(b) ? a : b);
                }
            }
        }
        final Map<UUID, Grants> grants = new HashMap<>();
        for (Map.Entry<UUID, Set<String>> entry : roles.entrySet())
            grants.put(entry.getKey(), new Grants(Set.copyOf(entry.getValue()), Set.copyOf(nodes.get(entry.getKey())),
                    expiries.get(entry.getKey())));
        return grants;
    }

    /**
     * The nodes a role grants.
     *
     * @param role the role id
     * @return its nodes, wildcards included; null if there is no such role
     * @throws SQLException if the database failed
     */
    @Nullable Set<String> nodesOf(String role) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT rp.node FROM role r LEFT JOIN role_permission rp ON rp.role_id = r.role_id WHERE r.role_id = ?")) {
            select.setString(1, role);
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) return null;
                final Set<String> nodes = new TreeSet<>();
                do {
                    final String node = rows.getString(1);
                    if (node != null) nodes.add(node);
                } while (rows.next());
                return nodes;
            }
        }
    }

    /**
     * Grants a role without an expiry; a grant that already exists loses its expiry.
     *
     * @param account the account, which need not have logged in yet
     * @param role    the role id
     * @return {@link Change#DONE}, or {@link Change#UNKNOWN_ROLE} if there is no such role
     * @throws SQLException if the database failed
     */
    Change grant(UUID account, String role) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO account_role (account_uuid, role_id, expires_at) VALUES (?, ?, NULL) "
                             + "ON CONFLICT (account_uuid, role_id) DO UPDATE SET expires_at = NULL")) {
            insert.setObject(1, account);
            insert.setString(2, role);
            insert.executeUpdate();
            return Change.DONE;
        } catch (SQLException e) {
            if (FOREIGN_KEY_VIOLATION.equals(e.getSQLState())) return Change.UNKNOWN_ROLE;
            throw e;
        }
    }

    /**
     * Grants a role on a player's behalf, for no longer than the player's own grants run: a new grant
     * and one that has expired take the given expiry, a grant in force is left as it is. So a holder
     * cannot lift the expiry of a grant in force, its own included, nor renew one past its own grants.
     *
     * @param account   the account, which need not have logged in yet
     * @param role      the role id
     * @param expiresAt the earliest expiry among the player's own grants, null if none of them expires
     * @return {@link Change#DONE}, or {@link Change#UNKNOWN_ROLE} if there is no such role
     * @throws SQLException if the database failed
     */
    Change handOut(UUID account, String role, @Nullable Instant expiresAt) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO account_role (account_uuid, role_id, expires_at) VALUES (?, ?, ?) "
                             + "ON CONFLICT (account_uuid, role_id) DO UPDATE SET expires_at = EXCLUDED.expires_at "
                             + "WHERE account_role.expires_at <= now()")) {
            insert.setObject(1, account);
            insert.setString(2, role);
            if (expiresAt == null) insert.setNull(3, Types.TIMESTAMP_WITH_TIMEZONE);
            else insert.setObject(3, expiresAt.atOffset(ZoneOffset.UTC));
            insert.executeUpdate();
            return Change.DONE;
        } catch (SQLException e) {
            if (FOREIGN_KEY_VIOLATION.equals(e.getSQLState())) return Change.UNKNOWN_ROLE;
            throw e;
        }
    }

    /**
     * Takes a role back.
     *
     * @param account the account
     * @param role    the role id
     * @return {@link Change#DONE}, or {@link Change#NOT_GRANTED} if the account did not hold it
     * @throws SQLException if the database failed
     */
    Change revoke(UUID account, String role) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM account_role WHERE account_uuid = ? AND role_id = ?")) {
            delete.setObject(1, account);
            delete.setString(2, role);
            return delete.executeUpdate() == 0 ? Change.NOT_GRANTED : Change.DONE;
        }
    }
}
