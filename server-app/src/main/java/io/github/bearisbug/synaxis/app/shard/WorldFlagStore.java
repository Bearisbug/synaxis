package io.github.bearisbug.synaxis.app.shard;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The SQL of the world state flags ({@code docs/DESIGN-M3.md} {@code API-M3-013}): the rows of
 * {@code world_flag} of one map and one region. Every statement carries both, so that a shard reads and
 * writes the flags of its own region only (§10). The statements run on the pool of the player store,
 * whose connections carry the statement timeout of 2 s ({@code docs/DESIGN-PROD.md} {@code API-PROD-004}).
 */
final class WorldFlagStore {

    // Ordered by key, so that every channel replays the keys in the same order
    private static final String READ_ALL = """
            SELECT flag_key, value::text, version FROM world_flag WHERE map_id = ? AND region_id = ? ORDER BY flag_key""";
    private static final String READ = """
            SELECT flag_key, value::text, version FROM world_flag WHERE map_id = ? AND region_id = ? AND flag_key = ?""";
    private static final String INSERT = """
            INSERT INTO world_flag (map_id, region_id, flag_key, value, version) VALUES (?, ?, ?, ?::jsonb, 1)
            ON CONFLICT DO NOTHING""";
    private static final String UPDATE = """
            UPDATE world_flag SET value = ?::jsonb, version = version + 1, updated_at = now()
            WHERE map_id = ? AND region_id = ? AND flag_key = ? AND version = ?""";

    /**
     * A flag as the table has it.
     *
     * @param key     the flag key
     * @param value   its value
     * @param version its version
     */
    record Row(String key, JsonElement value, long version) {
        FlagValue flagValue() {
            return new FlagValue(value, version);
        }
    }

    private final DataSource dataSource;
    private final String mapId;
    private final String regionId;

    WorldFlagStore(DataSource dataSource, String mapId, String regionId) {
        this.dataSource = dataSource;
        this.mapId = mapId;
        this.regionId = regionId;
    }

    /** Every flag of the region, by key; a value that does not parse fails the read. */
    List<Row> readAll() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(READ_ALL)) {
            select.setString(1, mapId);
            select.setString(2, regionId);
            final List<Row> rows = new ArrayList<>();
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) rows.add(row(result));
            }
            return rows;
        }
    }

    /** One flag of the region, null if the region has no such key. */
    @Nullable Row read(String key) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(READ)) {
            select.setString(1, mapId);
            select.setString(2, regionId);
            select.setString(3, key);
            try (ResultSet result = select.executeQuery()) {
                return result.next() ? row(result) : null;
            }
        }
    }

    /**
     * Writes a new key as version 1.
     *
     * @return false if the key is there already
     */
    boolean insert(String key, JsonElement value) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(INSERT)) {
            insert.setString(1, mapId);
            insert.setString(2, regionId);
            insert.setString(3, key);
            insert.setString(4, value.toString());
            return insert.executeUpdate() == 1;
        }
    }

    /**
     * Writes a key as {@code expectedVersion + 1}, only if it is at {@code expectedVersion}.
     *
     * @return false if the key is at another version, or not there
     */
    boolean update(String key, JsonElement value, long expectedVersion) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(UPDATE)) {
            update.setString(1, value.toString());
            update.setString(2, mapId);
            update.setString(3, regionId);
            update.setString(4, key);
            update.setLong(5, expectedVersion);
            return update.executeUpdate() == 1;
        }
    }

    private static Row row(ResultSet result) throws SQLException {
        final String key = result.getString(1);
        try {
            return new Row(key, JsonParser.parseString(result.getString(2)), result.getLong(3));
        } catch (JsonParseException e) {
            // PostgreSQL takes a jsonb Gson cannot read back, nested past its 255 levels
            throw new SQLException("the value of world flag " + key + " does not parse: " + e.getMessage(), e);
        }
    }
}
