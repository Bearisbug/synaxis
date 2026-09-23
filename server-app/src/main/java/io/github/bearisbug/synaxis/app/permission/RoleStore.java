package io.github.bearisbug.synaxis.app.permission;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
     * @param roles the role ids
     * @param nodes the nodes they grant, wildcards included
     */
    record Grants(Set<String> roles, Set<String> nodes) {
        static final Grants NONE = new Grants(Set.of(), Set.of());
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
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT ar.account_uuid, ar.role_id, rp.node FROM account_role ar "
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
                }
            }
        }
        final Map<UUID, Grants> grants = new HashMap<>();
        for (Map.Entry<UUID, Set<String>> entry : roles.entrySet())
            grants.put(entry.getKey(), new Grants(Set.copyOf(entry.getValue()), Set.copyOf(nodes.get(entry.getKey()))));
        return grants;
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
