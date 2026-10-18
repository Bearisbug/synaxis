package io.github.bearisbug.synaxis.app.store;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SystemChatPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.plugin.DataSection;
import io.github.bearisbug.synaxis.plugin.SaveResult;
import io.github.bearisbug.synaxis.testing.Env;
import net.kyori.adventure.text.TranslatableComponent;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * What the player store tests share: a section, players on a connection that really disconnects,
 * stores on a real database and the ledger lines they write.
 */
final class StoreFixture {

    /** Renewal and autosave driven by the test. */
    static final ShardSession.Timing MANUAL = new ShardSession.Timing(Duration.ofHours(1),
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(30));
    static final PlayerStore.Options FAST = new PlayerStore.Options(Duration.ofSeconds(5), 2, 4,
            List.of(Duration.ofMillis(50)), Duration.ofSeconds(1), Duration.ofMillis(50));

    private StoreFixture() {
    }

    /** A mutable purse, the model of {@link #PURSE}. */
    static final class Purse {
        int coins;
        String note = "";
    }

    /** {@code probe:purse}, version 2; version 1 stored the coins under {@code gold}. */
    static final DataSection<Purse> PURSE = new DataSection<>() {
        @Override
        public String id() {
            return "probe:purse";
        }

        @Override
        public int schemaVersion() {
            return 2;
        }

        @Override
        public Purse createDefault() {
            return new Purse();
        }

        @Override
        public Purse decode(JsonElement json, int storedVersion) {
            final JsonObject object = json.getAsJsonObject();
            final Purse purse = new Purse();
            purse.coins = object.get(storedVersion == 1 ? "gold" : "coins").getAsInt();
            if (object.has("note")) purse.note = object.get("note").getAsString();
            return purse;
        }

        @Override
        public JsonElement encode(Purse value) {
            final JsonObject object = new JsonObject();
            object.addProperty("coins", value.coins);
            object.addProperty("note", value.note);
            return object;
        }
    };

    /** A store with a session of its own on the test database, hooked into the process. */
    record Shard(PlayerStore store, ShardSession session, List<String> ledger, EventNode<Event> node) {
        long sessionId() {
            return session.sessionId();
        }

        List<String> events(String name) {
            return ledger.stream().filter(line -> line.startsWith(name + " ")).toList();
        }
    }

    static Shard shard(Env env, Database pool, String shardId, boolean displace, RedisLink redis,
                       PlayerStore.Options options) throws SQLException {
        return shard(env, pool, shardId, displace, redis, options, MANUAL, new PostgresBackend(pool.pool()));
    }

    /** The same with the session's clock and the backend chosen by the test. */
    static Shard shard(Env env, Database pool, String shardId, boolean displace, RedisLink redis,
                       PlayerStore.Options options, ShardSession.Timing timing, StoreBackend backend) throws SQLException {
        return shard(env, pool, shardId, displace, redis, options, timing, backend, _ -> {
        });
    }

    /** The same, handing each ledger line to the test before it is recorded, on the thread that writes it. */
    static Shard shard(Env env, Database pool, String shardId, boolean displace, RedisLink redis,
                       PlayerStore.Options options, ShardSession.Timing timing, StoreBackend backend,
                       Consumer<String> beforeLine) throws SQLException {
        final List<String> ledger = new CopyOnWriteArrayList<>();
        final PlayerStore store = new PlayerStore(env.process(), backend, redis, shardId, displace, options,
                (event, attributes) -> {
                    final String line = event + " " + attributes;
                    beforeLine.accept(line);
                    ledger.add(line);
                });
        final ShardSession session = new ShardSession(pool::dedicated, shardId, timing, System::nanoTime,
                store::sessionChanged);
        store.useSession(session);
        store.register(PURSE);
        final EventNode<Event> node = EventNode.all("store-" + shardId);
        env.process().eventHandler().addChild(node);
        store.attach(node);
        session.open();
        return new Shard(store, session, ledger, node);
    }

    /** A connection whose disconnect is the real one, recording the reason it was kicked with. */
    static final class RecordingConnection extends PlayerConnection {
        final List<String> kicks = new CopyOnWriteArrayList<>();
        final List<String> messages = new CopyOnWriteArrayList<>();
        /** Every packet in the order it was sent, for a test that checks the order of two. */
        public final List<SendablePacket> sent = new CopyOnWriteArrayList<>();
        /** What the client answers a cookie request with. */
        public volatile byte @Nullable [] cookie;

        public RecordingConnection(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            sent.add(packet);
            if (packet instanceof DisconnectPacket disconnect
                    && disconnect.message() instanceof TranslatableComponent translatable)
                kicks.add(translatable.key());
            if (packet instanceof SystemChatPacket chat && chat.message() instanceof TranslatableComponent translatable)
                messages.add(translatable.key());
        }

        @Override
        public CompletableFuture<byte @Nullable []> fetchCookie(String key) {
            return CompletableFuture.completedFuture(cookie);
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("localhost", 25565);
        }
    }

    /**
     * A player in the configuration phase, which is where the host loads it.
     */
    static Player connect(ServerProcess process, UUID uuid, String name) {
        final RecordingConnection connection = new RecordingConnection(process);
        connection.setServerState(ConnectionState.CONFIGURATION);
        final Player player = process.connection().createPlayer(connection, new GameProfile(uuid, name));
        assertNotNull(player, "the uuid is free");
        return player;
    }

    /** Lets a connected player into the world, as the engine does once the admission gave an instance. */
    static void spawn(ServerProcess process, Player player, Instance instance) {
        enterPlay(process, player, instance);
        process.connection().updateWaitingPlayers();
    }

    /**
     * Finishes a connected player's configuration and queues it to join, as a client acknowledging
     * it does; it spawns on the next tick.
     */
    static void enterPlay(ServerProcess process, Player player, Instance instance) {
        player.eventNode().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(new Pos(0, 42, 0));
        });
        CompletableFuture.runAsync(() -> {
            process.connection().doConfiguration(player, false);
            process.connection().transitionConfigToPlay(player);
        }, Thread.ofVirtual()::start).join();
        final RecordingConnection connection = (RecordingConnection) player.getPlayerConnection();
        connection.setClientState(ConnectionState.PLAY);
        connection.setServerState(ConnectionState.PLAY);
    }

    /**
     * {@code saveNow} on the thread that owns the player, as a plugin's listener calls it: the
     * snapshot is taken there and then, not on the player's next tick.
     */
    static CompletableFuture<SaveResult> saveNowOwned(PlayerStore store, Player player) {
        final AtomicReference<CompletableFuture<SaveResult>> save = new AtomicReference<>();
        player.acquirable().sync(_ -> save.set(store.saveNow(player)));
        return save.get();
    }

    static List<String> messages(Player player) {
        return ((RecordingConnection) player.getPlayerConnection()).messages;
    }

    static List<String> kicks(Player player) {
        return ((RecordingConnection) player.getPlayerConnection()).kicks;
    }

    static void await(Env env, BooleanSupplier condition, String what) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) throw new AssertionError("not within 10 s: " + what);
            env.tick();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** The row of a character, read straight from the database. */
    record Row(long characterId, long version, @Nullable Long lease, @Nullable String purse, int schemaVersion) {
    }

    static @Nullable Row row(TestDatabase database, UUID account) throws SQLException {
        try (Connection connection = database.connect(); PreparedStatement select = connection.prepareStatement("""
                SELECT c.character_id, c.version, c.lease_session_id, s.data::text, s.schema_version
                  FROM character c LEFT JOIN character_section s
                    ON s.character_id = c.character_id AND s.section_id = 'probe:purse'
                 WHERE c.account_uuid = ?""")) {
            select.setObject(1, account);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) return null;
                final long lease = row.getLong(3);
                final Long leaseOrNull = row.wasNull() ? null : lease;
                return new Row(row.getLong(1), row.getLong(2), leaseOrNull, row.getString(4), row.getInt(5));
            }
        }
    }

    static int count(TestDatabase database, String sql) throws SQLException {
        try (Connection connection = database.connect(); PreparedStatement select = connection.prepareStatement(sql);
             ResultSet row = select.executeQuery()) {
            row.next();
            return row.getInt(1);
        }
    }

    /**
     * The database, with the next release statement held once asked to: it stays on its way to
     * the database until the test lets it go.
     */
    static final class ReleaseHeld implements StoreBackend {
        final CountDownLatch releasing = new CountDownLatch(1);
        final CountDownLatch letGo = new CountDownLatch(1);
        private final AtomicBoolean holdNext = new AtomicBoolean();
        private final PostgresBackend postgres;

        ReleaseHeld(Database pool) {
            this.postgres = new PostgresBackend(pool.pool());
        }

        void holdNextRelease() {
            holdNext.set(true);
        }

        @Override
        public LoadOutcome load(UUID account, String name, long sessionId) throws SQLException {
            return postgres.load(account, name, sessionId);
        }

        @Override
        public void write(long sessionId, List<Write> writes) throws FencedException, SQLException {
            postgres.write(sessionId, writes);
        }

        @Override
        public void release(long characterId, long sessionId) throws SQLException {
            if (holdNext.compareAndSet(true, false)) {
                releasing.countDown();
                try {
                    letGo.await();
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
            postgres.release(characterId, sessionId);
        }

        @Override
        public void releaseAccount(UUID account, long sessionId) throws SQLException {
            postgres.releaseAccount(account, sessionId);
        }
    }
}
