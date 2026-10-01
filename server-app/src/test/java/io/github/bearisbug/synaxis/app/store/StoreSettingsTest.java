package io.github.bearisbug.synaxis.app.store;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the boot self-check prints of the player store settings ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-004}): the JDBC URL down to its hosts and database, since a parameter such as
 * {@code sslpassword=} or a user info carries a secret as much as the password variable does.
 */
class StoreSettingsTest {

    private static StoreSettings settings(String url) {
        return StoreSettings.fromEnvironment(Map.of(
                "SYNAXIS_STATE_DB_URL", url,
                "SYNAXIS_STATE_DB_USER", "shard",
                "SYNAXIS_STATE_DB_PASSWORD", "Db-P4ss",
                "SYNAXIS_REDIS_URL", "redis://:R3d1s-Pw@valkey:6379"));
    }

    @Test
    void theJdbcUrlIsPrintedWithoutItsParameters() {
        assertEquals("StoreSettings[jdbcUrl=jdbc:postgresql://db:5432/synaxis, user=shard, password=<set>, redis=<set>]",
                settings("jdbc:postgresql://db:5432/synaxis?sslmode=verify-full&sslpassword=K3yS3cret&password=P4ss")
                        .toString());
    }

    @Test
    void theJdbcUrlIsPrintedWithoutAUserInfo() {
        assertEquals("StoreSettings[jdbcUrl=jdbc:postgresql://db1:5432,db2:5433/synaxis, user=shard, password=<set>, "
                        + "redis=<set>]",
                settings("jdbc:postgresql://admin:Us3r/Pw@db1:5432,db2:5433/synaxis#K3yS3cret").toString());
    }
}
