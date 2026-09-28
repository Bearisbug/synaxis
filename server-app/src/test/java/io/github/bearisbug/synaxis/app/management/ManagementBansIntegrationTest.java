package io.github.bearisbug.synaxis.app.management;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.app.command.HostCommands;
import io.github.bearisbug.synaxis.app.command.Profiler;
import io.github.bearisbug.synaxis.app.moderation.HostModeration;
import io.github.bearisbug.synaxis.app.moderation.ModerationStore;
import io.github.bearisbug.synaxis.app.store.AuthMode;
import io.github.bearisbug.synaxis.app.store.Database;
import io.github.bearisbug.synaxis.app.store.Migrations;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.app.store.TestDatabase;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.BanTarget;
import io.github.bearisbug.synaxis.plugin.Permissions;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.bearisbug.synaxis.app.management.ManagementSettingsTest.TOKEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code minecraft:bans}, {@code minecraft:bans/add} and {@code minecraft:bans/remove} over a real
 * WebSocket against the ban tables of PostgreSQL ({@code docs/DESIGN-PROD.md} {@code API-PROD-022},
 * {@code API-PROD-009}). Needs the shared test database of {@code docs/TEST.md} §3.
 */
@EnvTest
class ManagementBansIntegrationTest {

    @TempDir
    Path workingDirectory;

    private TestDatabase database;
    private Database pool;
    private final List<Map<String, ?>> events = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> opened = new ArrayList<>();
    private HostModeration moderation;

    @BeforeEach
    void create() throws SQLException {
        database = TestDatabase.create();
        pool = new Database(database.settings());
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
    }

    @AfterEach
    void drop() throws Exception {
        for (AutoCloseable closeable : opened.reversed()) closeable.close();
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    private ManagementClient serve(Env env) throws Exception {
        moderation = new HostModeration(env.process(), new ModerationStore(pool.pool()),
                RedisLink.disabled("bans"), HostModeration.REFRESH, false, (_, _) -> { });
        moderation.start();
        final PlayerStore store = PlayerStore.inMemory(env.process(), (_, _) -> { });
        final Permissions none = new Permissions() {
            @Override
            public boolean has(Player player, String node) {
                return false;
            }

            @Override
            public Set<String> roles(Player player) {
                return Set.of();
            }
        };
        final HostCommands commands = new HostCommands(env.process(), none, store, (_, _) -> { },
                new Profiler(workingDirectory.resolve("profiles"), "test", Clock.systemUTC()));
        final ManagementMethods methods = new ManagementMethods(env.process(), commands, store, moderation, null, null, "test",
                (_, attributes) -> events.add(attributes));
        final ManagementServer server = ManagementServer.serve(ManagementSettings.parse("true", "127.0.0.1:0", TOKEN),
                env.process(), methods, Duration.ofSeconds(5));
        opened.add(server);
        final InetSocketAddress address = server.address();
        final ManagementClient client = ManagementClient.connect(address, "Bearer " + TOKEN);
        opened.add(client);
        return client;
    }

    private List<String> managementCommands() {
        return events.stream().map(event -> event.get("command") + " " + event.get("target") + " " + event.get("result"))
                .toList();
    }

    private static @Nullable JsonObject entry(JsonArray list, UUID account) {
        for (var element : list) {
            final JsonObject ban = element.getAsJsonObject();
            if (account.toString().equals(ban.getAsJsonObject("player").get("id").getAsString())) return ban;
        }
        return null;
    }

    @Test
    void bansAreListedAddedAllOrNothingAndRemoved(Env env) throws Exception {
        final ManagementClient client = serve(env);
        final Player online = env.createPlayer(env.createFlatInstance(), new Pos(0, 41, 0));
        final UUID absent = UUID.randomUUID();
        final UUID lapsed = UUID.randomUUID();
        final Instant expires = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        assertEquals(new JsonArray(), client.call(1, "minecraft:bans", null).get("result"));

        // One entry expired: nothing is banned, the valid entry included
        final JsonObject refused = client.call(2, "minecraft:bans/add", "[[{\"player\":{\"id\":\"" + absent + "\"}},"
                + "{\"player\":{\"id\":\"" + lapsed + "\"},\"expires\":\"2020-01-01T00:00:00Z\"}]]");
        assertEquals(-32602, refused.getAsJsonObject("error").get("code").getAsInt(), refused.toString());
        assertEquals(new JsonArray(), client.call(3, "minecraft:bans", null).get("result"));
        assertEquals(-32602, client.call(4, "minecraft:bans/add",
                "{\"add\":[{\"player\":{\"name\":\"NotHere\"}}]}").getAsJsonObject("error").get("code").getAsInt());
        assertEquals(-32602, client.call(5, "minecraft:bans/add",
                "{\"add\":[{\"player\":{\"id\":\"" + absent + "\"},\"reason\":\"" + "r".repeat(257) + "\"}]}")
                .getAsJsonObject("error").get("code").getAsInt());

        // An account that never logged in by id, one online here by name
        final JsonArray added = client.call(6, "minecraft:bans/add", "{\"add\":[{\"player\":{\"id\":\"" + absent
                + "\"},\"reason\":\"griefing\",\"expires\":\"" + expires + "\"},{\"player\":{\"name\":\""
                + online.getUsername() + "\"}}]}").getAsJsonArray("result");
        assertEquals(2, added.size(), added.toString());
        assertEquals(JsonParser.parseString("{\"player\":{\"id\":\"" + absent + "\"},\"reason\":\"griefing\",\"expires\":\""
                + expires + "\"}"), entry(added, absent));
        assertEquals(JsonParser.parseString("{\"player\":{\"id\":\"" + online.getUuid() + "\"},\"reason\":\""
                + ManagementMethods.DEFAULT_BAN_REASON + "\"}"), entry(added, online.getUuid()));
        assertEquals(added, client.call(7, "minecraft:bans", null).get("result"));
        assertFalse(online.isOnline(), "a ban kicks who it covers");
        database.execute("DO $$ BEGIN IF (SELECT count(*) FROM ban WHERE revoked_at IS NULL AND created_by = "
                + "'00000000-0000-0000-0000-000000000000') <> 2 THEN RAISE EXCEPTION 'not the console operator'; END IF; END $$");

        final JsonArray remaining = client.call(8, "minecraft:bans/remove", "[[{\"id\":\"" + absent + "\"}]]")
                .getAsJsonArray("result");
        assertEquals(1, remaining.size(), remaining.toString());
        assertTrue(entry(remaining, online.getUuid()) != null, remaining.toString());
        assertEquals(remaining, client.call(9, "minecraft:bans/remove", "{\"remove\":[{\"id\":\"" + absent + "\"}]}")
                .get("result"), "nothing left to revoke");

        assertEquals(List.of(
                "minecraft:bans null ok",
                "minecraft:bans/add [[{\"player\":{\"id\":\"" + absent + "\"}},{\"player\":{\"id\":\"" + lapsed
                        + "\"},\"expires\":\"2020-01-01T00:00:00Z\"}]] null invalid",
                "minecraft:bans null ok",
                "minecraft:bans/add {\"add\":[{\"player\":{\"name\":\"NotHere\"}}]} null invalid",
                "minecraft:bans/add {\"add\":[{\"player\":{\"id\":\"" + absent + "\"},\"reason\":\"" + "r".repeat(257)
                        + "\"}]} null invalid",
                "minecraft:bans/add {\"add\":[{\"player\":{\"id\":\"" + absent + "\"},\"reason\":\"griefing\",\"expires\":\""
                        + expires + "\"},{\"player\":{\"name\":\"" + online.getUsername() + "\"}}]} null ok",
                "minecraft:bans null ok",
                "minecraft:bans/remove [[{\"id\":\"" + absent + "\"}]] " + absent + " ok",
                "minecraft:bans/remove {\"remove\":[{\"id\":\"" + absent + "\"}]} " + absent + " no_ban"),
                managementCommands());
    }

    /**
     * An expiry the ban table cannot hold is a bad parameter like a past one: {@code bans/add} checks
     * it with the rest of the batch and answers −32602 before the first ban is written, the plugin
     * API throws before the database ({@code docs/DESIGN-PROD.md} {@code API-PROD-022},
     * {@code API-PROD-009}). It used to reach the driver: −32603 with a WARN and its stack, or −32000
     * for a write that could never succeed, the entries before it banned already.
     */
    @Test
    void anExpiryTheBanTableCannotHoldIsRefusedBeforeAnyBanIsWritten(Env env) throws Exception {
        final ManagementClient client = serve(env);
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();
        final List<String> tooLate = List.of("+1000000000-12-31T23:59:59.999999999Z", "+1000000-01-01T00:00:00Z",
                "+294276-12-31T23:59:59Z", "+10000-01-01T00:00:00Z");
        int id = 1;
        for (String expires : tooLate) {
            final JsonObject refused = client.call(id++, "minecraft:bans/add", "[[{\"player\":{\"id\":\"" + first
                    + "\"},\"expires\":\"2031-01-01T00:00:00Z\"},{\"player\":{\"id\":\"" + second
                    + "\"},\"expires\":\"" + expires + "\"}]]");
            assertEquals(-32602, refused.getAsJsonObject("error").get("code").getAsInt(), expires + " -> " + refused);
            assertTrue(refused.getAsJsonObject("error").get("message").getAsString()
                    .endsWith("is not before +10000-01-01T00:00:00Z"), refused.toString());
            assertEquals(new JsonArray(), client.call(id++, "minecraft:bans", null).get("result"),
                    "the valid entry before it is not banned either: " + expires);
        }

        final JsonArray added = client.call(id, "minecraft:bans/add", "[[{\"player\":{\"id\":\"" + second
                + "\"},\"expires\":\"9999-12-31T23:59:59Z\"}]]").getAsJsonArray("result");
        final JsonObject last = entry(added, second);
        assertEquals("9999-12-31T23:59:59Z", last == null ? null : last.get("expires").getAsString(), added.toString());
        assertThrows(IllegalArgumentException.class, () -> moderation.ban(new BanTarget.ByUuid(first), "late",
                Instant.MAX, HostModeration.CONSOLE), "the plugin API refuses it before the database");
        assertEquals(List.of("invalid", "invalid", "invalid", "invalid", "ok"), managementCommands().stream()
                .filter(command -> command.startsWith("minecraft:bans/add"))
                .map(command -> command.substring(command.lastIndexOf(' ') + 1)).toList());
    }
}
