package io.github.bearisbug.synaxis.app.store;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The statements of {@code docs/DESIGN-PROD.md} {@code API-PROD-004}. Time is only ever the
 * database's {@code now()}, so the clocks of the shards do not matter to who holds a lease.
 */
public final class PostgresBackend implements StoreBackend {

    private static final String UPSERT_ACCOUNT = """
            INSERT INTO account (account_uuid, last_name) VALUES (?, ?)
            ON CONFLICT (account_uuid) DO UPDATE SET last_login_at = now(), last_name = EXCLUDED.last_name""";
    // The lease is never written here: only the acquire statement below takes it (API-PROD-004)
    private static final String INSERT_CHARACTER = """
            INSERT INTO character (account_uuid, slot, version, lease_session_id) VALUES (?, 0, 0, NULL)
            ON CONFLICT (account_uuid, slot) DO NOTHING""";
    // Acquire: nobody holds it, this session holds it, or its holder's session has expired. The
    // locked read before it is what reports the previous holder of a takeover.
    private static final String ACQUIRE = """
            WITH previous AS (
                SELECT character_id, lease_session_id, last_saved_at FROM character
                 WHERE account_uuid = ? AND slot = 0 FOR UPDATE)
            UPDATE character c SET lease_session_id = ?
              FROM previous p
             WHERE c.character_id = p.character_id
               AND (c.lease_session_id IS NULL
                    OR c.lease_session_id = ?
                    OR NOT EXISTS (SELECT 1 FROM shard_session s
                                    WHERE s.session_id = c.lease_session_id AND s.expires_at > now()))
            RETURNING c.character_id, c.version, p.lease_session_id, p.last_saved_at""";
    private static final String HOLDER = """
            SELECT s.shard_id FROM character c JOIN shard_session s ON s.session_id = c.lease_session_id
             WHERE c.account_uuid = ? AND c.slot = 0""";
    private static final String SECTIONS = """
            SELECT section_id, schema_version, data::text FROM character_section WHERE character_id = ?""";
    private static final String FENCE = """
            UPDATE character SET version = version + 1, last_saved_at = now()
             WHERE character_id = ? AND lease_session_id = ? AND version = ?""";
    private static final String UPSERT_SECTION = """
            INSERT INTO character_section (character_id, section_id, schema_version, data, updated_at)
            VALUES (?, ?, ?, ?::jsonb, now())
            ON CONFLICT (character_id, section_id)
            DO UPDATE SET schema_version = EXCLUDED.schema_version, data = EXCLUDED.data, updated_at = now()""";
    private static final String RELEASE = """
            UPDATE character SET lease_session_id = NULL WHERE character_id = ? AND lease_session_id = ?""";
    private static final String RELEASE_ACCOUNT = """
            UPDATE character SET lease_session_id = NULL WHERE account_uuid = ? AND slot = 0 AND lease_session_id = ?""";

    private final DataSource dataSource;

    public PostgresBackend(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public LoadOutcome load(UUID account, String name, long sessionId) throws SQLException {
        // One transaction: a load that fails after its acquire, reading the sections, holds no lease
        return Sql.inTransaction(dataSource, connection -> load(connection, account, name, sessionId));
    }

    private static LoadOutcome load(Connection connection, UUID account, String name, long sessionId) throws SQLException {
        try (PreparedStatement upsert = connection.prepareStatement(UPSERT_ACCOUNT)) {
            upsert.setObject(1, account);
            upsert.setString(2, name);
            upsert.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(INSERT_CHARACTER)) {
            insert.setObject(1, account);
            insert.executeUpdate();
        }
        // A holder whose lease is released between the two statements makes the second find
        // nobody, and the acquire is simply tried again
        for (int attempt = 0; ; attempt++) {
            try (PreparedStatement acquire = connection.prepareStatement(ACQUIRE)) {
                acquire.setObject(1, account);
                acquire.setLong(2, sessionId);
                acquire.setLong(3, sessionId);
                try (ResultSet row = acquire.executeQuery()) {
                    if (row.next()) {
                        final long characterId = row.getLong(1);
                        final long version = row.getLong(2);
                        final long previous = row.getLong(3);
                        final Long previousSession = row.wasNull() || previous == sessionId ? null : previous;
                        final Timestamp lastSaved = row.getTimestamp(4);
                        return new LoadOutcome.Acquired(characterId, version, sections(connection, characterId),
                                previousSession, lastSaved == null ? null : lastSaved.toInstant());
                    }
                }
            }
            try (PreparedStatement holder = connection.prepareStatement(HOLDER)) {
                holder.setObject(1, account);
                try (ResultSet row = holder.executeQuery()) {
                    if (row.next()) return new LoadOutcome.Held(row.getString(1));
                }
            }
            if (attempt > 0) throw new SQLException("character of " + account + " neither acquired nor held");
        }
    }

    private static Map<String, StoredSection> sections(Connection connection, long characterId) throws SQLException {
        final Map<String, StoredSection> sections = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement(SECTIONS)) {
            select.setLong(1, characterId);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) sections.put(row.getString(1), new StoredSection(row.getInt(2), row.getString(3)));
            }
        }
        return sections;
    }

    @Override
    public void write(long sessionId, List<Write> writes) throws FencedException, SQLException {
        Sql.inTransaction(dataSource, connection -> {
            // One order for every transaction, so two commits over the same characters cannot deadlock
            for (Write write : writes.stream().sorted(Comparator.comparingLong(Write::characterId)).toList()) {
                try (PreparedStatement fence = connection.prepareStatement(FENCE)) {
                    fence.setLong(1, write.characterId());
                    fence.setLong(2, sessionId);
                    fence.setLong(3, write.expectedVersion());
                    if (fence.executeUpdate() != 1) throw new FencedException(write.characterId());
                }
                if (!write.sections().isEmpty()) {
                    try (PreparedStatement upsert = connection.prepareStatement(UPSERT_SECTION)) {
                        for (Map.Entry<String, StoredSection> section : write.sections().entrySet()) {
                            upsert.setLong(1, write.characterId());
                            upsert.setString(2, section.getKey());
                            upsert.setInt(3, section.getValue().schemaVersion());
                            upsert.setString(4, section.getValue().json());
                            upsert.addBatch();
                        }
                        upsert.executeBatch();
                    }
                }
                if (write.release()) release(connection, write.characterId(), sessionId);
            }
            return null;
        });
    }

    @Override
    public void release(long characterId, long sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            release(connection, characterId, sessionId);
        }
    }

    @Override
    public void releaseAccount(UUID account, long sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement release = connection.prepareStatement(RELEASE_ACCOUNT)) {
            release.setObject(1, account);
            release.setLong(2, sessionId);
            release.executeUpdate();
        }
    }

    private static void release(Connection connection, long characterId, long sessionId) throws SQLException {
        try (PreparedStatement release = connection.prepareStatement(RELEASE)) {
            release.setLong(1, characterId);
            release.setLong(2, sessionId);
            release.executeUpdate();
        }
    }
}
