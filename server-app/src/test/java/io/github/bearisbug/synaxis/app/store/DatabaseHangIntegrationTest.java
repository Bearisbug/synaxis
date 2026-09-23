package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static io.github.bearisbug.synaxis.app.store.StoreFixture.connect;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.kicks;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.spawn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A database that stops answering without closing its connections, the way a paused container or
 * a network that drops packets does ({@code docs/DESIGN-PROD.md} §15 "PostgreSQL 暂停 60 s", §19
 * "数据库暂停时冻结再踢出"). The shared {@code synaxis-pg} cannot be paused, so the shard reaches it
 * through a proxy of the test that stops passing bytes on.
 */
@EnvTest
class DatabaseHangIntegrationTest {

    /** Renewal every 200 ms; freeze after 1 s and isolate after 3 s without one. */
    private static final ShardSession.Timing QUICK = new ShardSession.Timing(Duration.ofMillis(200),
            Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(30));

    private TestDatabase database;
    private Blackhole blackhole;
    private Database pool;
    private StoreFixture.Shard shard;

    @BeforeEach
    void create() throws IOException, SQLException {
        database = TestDatabase.create();
        try (Database direct = new Database(database.settings())) {
            Migrations.migrate(direct.pool(), AuthMode.OFFLINE);
        }
        final URI target = URI.create(database.settings().jdbcUrl().substring("jdbc:".length()));
        blackhole = new Blackhole(target.getHost(), target.getPort());
        final StoreSettings direct = database.settings();
        pool = new Database(new StoreSettings("jdbc:postgresql://127.0.0.1:" + blackhole.port() + target.getPath(),
                direct.user(), direct.password(), direct.redisUrl()));
    }

    @AfterEach
    void drop() {
        if (blackhole != null) blackhole.close();
        if (shard != null) shard.store().close();
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    @Test
    void aStatementTheDatabaseStopsAnsweringFailsInsteadOfWaitingForever() throws Exception {
        final Connection connection = pool.pool().getConnection();
        try {
            blackhole.stop();
            final long start = System.nanoTime();
            final CompletableFuture<Throwable> outcome = CompletableFuture.supplyAsync(() -> {
                try (PreparedStatement select = connection.prepareStatement("SELECT 1")) {
                    select.executeQuery().close();
                    return null;
                } catch (SQLException e) {
                    return e;
                }
            }, Thread.ofVirtual()::start);

            final Throwable failure = outcome.get(15, TimeUnit.SECONDS);
            final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(failure instanceof SQLException, "the statement fails: " + failure);
            assertTrue(elapsedMs <= Database.SOCKET_TIMEOUT_MS + 1_000, "within the socket timeout: " + elapsedMs + " ms");
        } finally {
            // Cutting the proxy's sockets is what ends a statement still waiting
            blackhole.close();
            try {
                connection.close();
            } catch (SQLException _) {
                // Cut by the proxy
            }
        }
    }

    @Test
    void aDatabaseThatStopsAnsweringFreezesThenKicksAndTheShardComesBack(Env env) throws Exception {
        shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"), StoreFixture.FAST,
                QUICK, new PostgresBackend(pool.pool()));
        final Instance instance = env.createFlatInstance();
        final Player player = connect(env.process(), UUID.randomUUID(), "Stranded");
        assertTrue(shard.store().load(player));
        shard.store().admitted(player);
        spawn(env.process(), player, instance);

        blackhole.stop();
        final long stopped = System.nanoTime();
        await(() -> shard.store().isFrozen(player), 3_000, "frozen");
        final long frozenMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopped);
        await(() -> !kicks(player).isEmpty(), 5_000, "kicked");
        final long kickedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopped);

        assertTrue(frozenMs >= 600 && frozenMs <= 2_000, "frozen 1 s after the last renewal: " + frozenMs + " ms");
        assertTrue(kickedMs >= 2_600 && kickedMs <= 4_000, "kicked 3 s after the last renewal: " + kickedMs + " ms");
        assertEquals(List.of("synaxis:state-unavailable"), kicks(player));
        assertEquals(ShardSession.State.ISOLATED, shard.session().state());

        blackhole.resume();
        await(() -> shard.session().state() == ShardSession.State.ACTIVE, 10_000, "a new epoch once it answers again");
        assertTrue(shard.store().load(connect(env.process(), UUID.randomUUID(), "Returning")), "logins are taken again");
    }

    private static void await(BooleanSupplier condition, long millis, String what) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) throw new AssertionError("not within " + millis + " ms: " + what);
            Thread.sleep(20);
        }
    }

    /**
     * A TCP proxy that can stop passing bytes on while keeping every connection open, and keeps
     * accepting new ones it passes nothing to.
     */
    private static final class Blackhole implements AutoCloseable {
        private final ServerSocket server;
        private final String host;
        private final int targetPort;
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private volatile boolean stopped;

        Blackhole(String host, int targetPort) throws IOException {
            this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            this.host = host;
            this.targetPort = targetPort;
            Thread.ofVirtual().name("blackhole-accept").start(this::accept);
        }

        int port() {
            return server.getLocalPort();
        }

        void stop() {
            stopped = true;
        }

        void resume() {
            stopped = false;
        }

        private void accept() {
            while (!server.isClosed()) {
                try {
                    final Socket client = server.accept();
                    final Socket upstream = new Socket(host, targetPort);
                    sockets.add(client);
                    sockets.add(upstream);
                    Thread.ofVirtual().start(() -> pump(client, upstream));
                    Thread.ofVirtual().start(() -> pump(upstream, client));
                } catch (IOException _) {
                    // Closed
                }
            }
        }

        private void pump(Socket from, Socket to) {
            final byte[] buffer = new byte[8192];
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    while (stopped) Thread.sleep(10);
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException | InterruptedException _) {
                // One side went away
            } finally {
                closeQuietly(from);
                closeQuietly(to);
            }
        }

        private static void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException _) {
                // Already closed
            }
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (IOException _) {
                // Already closed
            }
            for (Socket socket : sockets) closeQuietly(socket);
        }
    }
}
