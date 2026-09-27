package io.github.bearisbug.synaxis.loadtest;

import com.google.gson.JsonObject;
import io.github.bearisbug.synaxis.Auth;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerPreLoginEvent;
import io.github.bearisbug.synaxis.event.player.PlayerMoveEvent;
import io.github.bearisbug.synaxis.event.player.PlayerSpawnEvent;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/DESIGN-PROD.md} {@code REQ-PROD-029}: bots against a real ticking server on loopback,
 * a plain and an encrypted login, a kick counted as a lost bot, chunk batch receipts that keep the chunks coming, and the packet
 * rates the run file reports; and the transfers of a gate bot ({@code docs/DESIGN-M3.md} §26 M3-E track D).
 */
public class BotSocketTest {

    private static final int BOTS = 3;
    private static final String KICK = "kicked by the test";
    /** The frame of the gate tests: from 7.5 blocks east of the spawn, 4 blocks deep, about the spawn height. */
    private static final String GATE = "8,38,-2,11,42,2";
    private static final String TRANSFER_COOKIE = "synaxis:transfer";

    @Test
    @Timeout(90)
    public void walkingBotsAcknowledgeChunkBatchesAndGetMoreThanTheFirstNine(@TempDir Path directory) throws Exception {
        final JsonObject run = runAgainst(new Auth.Offline(false), "off", false, 0, directory.resolve("bots-0.json"));
        assertEquals(BOTS, run.getAsJsonObject("bots").get("logged_in").getAsInt(), run.toString());
        assertEquals(0, run.getAsJsonObject("bots").get("lost").getAsInt(), run.toString());
        final JsonObject walk = run.getAsJsonObject("inbound").getAsJsonObject("chunks").getAsJsonObject("walk");
        final Histogram perBot = Histogram.fromJson(walk.getAsJsonObject("per_bot_packets"));
        assertEquals(BOTS, perBot.count());
        assertTrue(perBot.min() > 9, "every bot got past the first batch of 9: " + perBot.min());
        assertTrue(run.get("chunk_batches").getAsLong() > BOTS, "batches were acknowledged");
        assertTrue(Histogram.fromJson(run.getAsJsonObject("chunk_batch_rtt_ms")).count() > 0);
        assertTrue(walk.get("steady_packets").getAsLong() > 0, "walking brings new chunks after the warm up");

        // A position packet and a tick end every tick: 40 a second, plus the odd keep-alive and receipt
        final Histogram perSecond = Histogram.fromJson(run.getAsJsonObject("c2s").getAsJsonObject("per_bot_per_1s"));
        assertTrue(perSecond.count() >= BOTS * 3L, "whole seconds in play were counted: " + perSecond.count());
        final double median = perSecond.percentile(0.5);
        assertTrue(median >= 38 && median <= 46, "packets a second per bot: " + median);
        final Histogram perSeven = Histogram.fromJson(run.getAsJsonObject("c2s").getAsJsonObject("per_bot_per_7s"));
        assertTrue(perSeven.count() > 0, "seven second windows were counted");
        assertTrue(perSeven.percentile(0.5) >= 7 * 38, "a seven second window: " + perSeven.percentile(0.5));
        assertEquals(BOTS, Histogram.fromJson(run.getAsJsonObject("login_latency_ms").getAsJsonObject("off")).count(),
                "one login time per bot");
    }

    @Test
    @Timeout(90)
    public void botsAnswerTheEncryptionRequestOfAnEncryptingServer(@TempDir Path directory) throws Exception {
        final JsonObject run = runAgainst(new Auth.Offline(true), "on", false, 0, directory.resolve("bots-0.json"));
        assertEquals(BOTS, run.getAsJsonObject("bots").get("logged_in").getAsInt(), run.toString());
        assertEquals(BOTS, Histogram.fromJson(run.getAsJsonObject("login_latency_ms").getAsJsonObject("on")).count());
        assertTrue(Histogram.fromJson(run.getAsJsonObject("inbound").getAsJsonObject("chunks").getAsJsonObject("walk")
                .getAsJsonObject("per_bot_packets")).min() > 9, "chunks decrypt and keep coming");
    }

    @Test
    @Timeout(90)
    public void aBotTheServerKicksCountsAsLostWithTheReason(@TempDir Path directory) throws Exception {
        final JsonObject run = runAgainst(new Auth.Offline(false), "off", true, 0, directory.resolve("bots-0.json"));
        assertEquals(BOTS, run.getAsJsonObject("bots").get("logged_in").getAsInt(), run.toString());
        assertEquals(BOTS, run.getAsJsonObject("bots").get("lost").getAsInt(), run.toString());
        assertEquals(BOTS, run.getAsJsonObject("kicks_by_reason").get(KICK).getAsInt(), run.toString());
    }

    /** Each bot counts the players it is shown; with a limit of 1 the server shows it one of the other two. */
    @Test
    @Timeout(90)
    public void botsCountTheEntitiesTheServerShowsThem(@TempDir Path directory) throws Exception {
        final Histogram all = Histogram.fromJson(runAgainst(new Auth.Offline(false), "off", false, 0,
                directory.resolve("bots-0.json")).getAsJsonObject("visible_entities"));
        assertEquals(BOTS - 1, all.max(), "every bot sees the other two");
        final Histogram limited = Histogram.fromJson(runAgainst(new Auth.Offline(false), "off", false, 1,
                directory.resolve("bots-1.json")).getAsJsonObject("visible_entities"));
        assertEquals(1, limited.max(), "a limit of 1 shows one of the other two");
    }

    /**
     * {@code docs/DESIGN-M3.md} §26 M3-E track D: two servers that each send a gate bot to the other
     * the moment it steps into the frame, the way a shard's gate does, a cookie stored first. The bot
     * goes back and forth, each time connecting with intent transfer and answering both cookie requests
     * of the new server with the bytes the old one stored; every trip is timed from the first position
     * in the frame to the 9th chunk on the new connection, and the bot stays the one bot it logged in as.
     */
    @Test
    @Timeout(90)
    public void aGateBotFollowsTransfersBackAndForthWithTheCookieItWasGiven(@TempDir Path directory) throws Exception {
        final List<byte[]> stored = new CopyOnWriteArrayList<>();
        final List<Arrival> arrivals = new CopyOnWriteArrayList<>();
        try (Server first = new Server("first", transferConfig());
             Server second = new Server("second", transferConfig())) {
            first.recordArrivals(arrivals);
            second.recordArrivals(arrivals);
            first.transferAtTheGate(second, stored);
            second.transferAtTheGate(first, stored);
            final Path out = directory.resolve("bots-0.json");
            final int status = RunCommand.run(new Args(List.of("--port", Integer.toString(first.port()), "--bots", "1",
                    "--profile", "gate", "--gates", GATE, "--dwell", "1s", "--duration", "12s", "--warmup", "3s",
                    "--sample-every", "4s", "--out", out.toString())));
            assertEquals(0, status);
            final JsonObject run = Json.read(out);
            assertEquals(1, run.getAsJsonObject("bots").get("logged_in").getAsInt(), run.toString());
            assertEquals(0, run.getAsJsonObject("bots").get("lost").getAsInt(), run.toString());
            assertEquals(1, Histogram.fromJson(run.getAsJsonObject("login_latency_ms").getAsJsonObject("off")).count(),
                    "a transfer is not another login of the bot");

            final JsonObject transfers = run.getAsJsonObject("transfers");
            final long completed = transfers.get("completed").getAsLong();
            assertTrue(completed >= 2, "there and back at least: " + transfers);
            assertEquals(completed, transfers.get("count").getAsLong(), "no try failed: " + transfers);
            assertEquals(0, transfers.getAsJsonObject("failed_by_reason").size(), transfers.toString());
            final Histogram millis = Histogram.fromJson(transfers.getAsJsonObject("transfer_ms"));
            assertEquals(completed, millis.count());
            assertTrue(millis.min() > 0 && millis.max() < 10_000,
                    "a trip took " + millis.min() + " to " + millis.max() + " ms");

            // The first login is a login; each later one came from a Transfer and carried that transfer's cookie
            assertTrue(arrivals.size() >= completed + 1, arrivals.toString());
            assertFalse(arrivals.getFirst().transferred(), "the bot logs in to the first server");
            assertNull(arrivals.getFirst().loginCookie(), "and has no cookie to give yet");
            for (int i = 1; i < arrivals.size(); i++) {
                final Arrival arrival = arrivals.get(i);
                assertTrue(arrival.transferred(), "connection " + i + " has intent transfer");
                assertEquals(i % 2 == 1 ? "second" : "first", arrival.server(), "back and forth");
                assertArrayEquals(stored.get(i - 1), arrival.loginCookie(), "the cookie asked for during login");
                assertArrayEquals(stored.get(i - 1), arrival.configurationCookie(),
                        "the cookie asked for during configuration");
            }
        }
    }

    /**
     * The same frame on a server that never sends a Transfer: after the wait, shortened here from 30 s
     * to 1.5 s, the bot counts a {@code no-transfer}, walks back out until 3 blocks clear of the frame
     * and in again.
     */
    @Test
    @Timeout(60)
    public void aGateBotThatGetsNoTransferCountsItAndWalksOutAndInAgain() throws Exception {
        final Profile.Frame gate = new Args(List.of("--gates", GATE)).gates("gates").getFirst();
        final AtomicInteger entries = new AtomicInteger();
        final DoubleAccumulator lowest = new DoubleAccumulator(Math::min, Double.MAX_VALUE);
        final BotStats stats = new BotStats();
        try (Server server = new Server("server", transferConfig())) {
            final Set<Player> inside = ConcurrentHashMap.newKeySet();
            server.process.eventHandler().addListener(PlayerMoveEvent.class, event -> {
                final Pos to = event.getNewPosition();
                if (gate.contains(to.x(), to.y(), to.z())) {
                    if (inside.add(event.getPlayer())) entries.incrementAndGet();
                } else {
                    inside.remove(event.getPlayer());
                }
                if (entries.get() > 0) lowest.accumulate(to.x());
            });
            final Bot bot = new Bot(0, new Bot.Settings("127.0.0.1", server.port(), Profile.GATE, false, 9f, false, 3,
                    30_000, List.of(gate), 0.5, TimeUnit.MILLISECONDS.toNanos(1_500)), stats);
            final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
            final Thread thread = Thread.ofVirtual().start(() -> bot.run(end));
            thread.join(20_000);
            bot.close();
        }
        final LongAdder noTransfers = stats.transferFailures.get("no-transfer");
        assertTrue(noTransfers != null && noTransfers.sum() >= 1, stats.transferFailures.toString());
        assertEquals(0, stats.transferMillis.count(), "no try completed");
        assertTrue(entries.get() >= 2, "the bot walked into the frame again: " + entries.get());
        // Spawned 7.5 blocks west of the frame, heading east: 3 blocks past the edge at x 8 is x 5
        assertEquals(5.0, lowest.get(), 1e-6, "how far back it walked");
    }

    private static ServerConfig transferConfig() {
        return ServerConfig.builder().connectionGateTrustedNetworks("127.0.0.0/8").chunkViewDistance(4)
                .acceptTransfers(true).build();
    }

    /** A login seen by a test server: whether it came from a Transfer and the cookie it gave in each state. */
    private record Arrival(String server, boolean transferred, byte @Nullable [] loginCookie,
                           byte @Nullable [] configurationCookie) {
    }

    private static JsonObject runAgainst(Auth auth, String encryption, boolean kick, int maxVisibleEntities, Path out)
            throws Exception {
        try (Server server = new Server("server", auth, ServerConfig.builder()
                .connectionGateTrustedNetworks("127.0.0.0/8").chunkViewDistance(4)
                .maxVisibleEntitiesPerPlayer(maxVisibleEntities).build())) {
            final ServerProcess process = server.process;
            if (kick) process.eventHandler().addListener(PlayerSpawnEvent.class, event -> process.scheduler()
                    .buildTask(() -> event.getPlayer().kick(KICK)).delay(TaskSchedule.seconds(4)).schedule());
            final int status = RunCommand.run(new Args(List.of("--port", Integer.toString(server.port()),
                    "--bots", Integer.toString(BOTS), "--duration", "12s", "--warmup", "3s", "--sample-every", "4s",
                    "--encryption", encryption, "--out", out.toString())));
            assertEquals(0, status);
            return Json.read(out);
        }
    }

    /** A really ticking server on loopback: flat stone up to y 40, every player spawning at (0.5, 40, 0.5). */
    private static final class Server implements AutoCloseable {
        final ServerProcess process;
        final String name;
        private final Thread ticker;

        Server(String name, ServerConfig config) {
            this(name, new Auth.Offline(false), config);
        }

        Server(String name, Auth auth, ServerConfig config) {
            this.name = name;
            this.process = ServerProcess.of(auth, config);
            final InstanceContainer world = process.instance().createInstanceContainer();
            world.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
            process.eventHandler().addListener(AsyncPlayerConfigurationEvent.class, event -> {
                event.setSpawningInstance(world);
                event.getPlayer().setRespawnPoint(new Pos(0.5, 40, 0.5));
            });
            process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            process.dispatcher().start();
            this.ticker = Thread.ofPlatform().name("test-ticker").start(() -> {
                long next = System.nanoTime();
                while (!Thread.currentThread().isInterrupted()) {
                    process.ticker().tick(System.nanoTime());
                    next += TimeUnit.MILLISECONDS.toNanos(50);
                    LockSupport.parkNanos(next - System.nanoTime());
                }
            });
        }

        int port() {
            return process.server().getPort();
        }

        /** Asks every login for the transfer cookie, during login and again during configuration. */
        void recordArrivals(List<Arrival> arrivals) {
            final Map<PlayerConnection, Optional<byte[]>> duringLogin = new ConcurrentHashMap<>();
            process.eventHandler().addListener(AsyncPlayerPreLoginEvent.class, event ->
                    duringLogin.put(event.getConnection(), Optional.ofNullable(cookie(event.getConnection()))));
            process.eventHandler().addListener(AsyncPlayerConfigurationEvent.class, event -> {
                final PlayerConnection connection = event.getPlayer().getPlayerConnection();
                final byte[] login = duringLogin.remove(connection).orElse(null);
                arrivals.add(new Arrival(name, connection.wasTransferred(), login, cookie(connection)));
            });
        }

        /**
         * Sends a player that steps into the frame to {@code other}, once per connection: a cookie of its
         * own first, then the Transfer, as a shard's gate does.
         */
        void transferAtTheGate(Server other, List<byte[]> stored) {
            final Profile.Frame gate = new Args(List.of("--gates", GATE)).gates("gates").getFirst();
            final Set<Player> sent = ConcurrentHashMap.newKeySet();
            process.eventHandler().addListener(PlayerMoveEvent.class, event -> {
                final Pos to = event.getNewPosition();
                if (!gate.contains(to.x(), to.y(), to.z()) || !sent.add(event.getPlayer())) return;
                final byte[] cookie = ("ticket " + stored.size() + " from " + name).getBytes(StandardCharsets.UTF_8);
                stored.add(cookie);
                final PlayerConnection connection = event.getPlayer().getPlayerConnection();
                connection.storeCookie(TRANSFER_COOKIE, cookie);
                connection.transfer("127.0.0.1", other.port());
            });
        }

        private static byte @Nullable [] cookie(PlayerConnection connection) {
            try {
                return connection.fetchCookie(TRANSFER_COOKIE).get(5, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new IllegalStateException("the bot did not answer the cookie request", e);
            }
        }

        @Override
        public void close() {
            ticker.interrupt();
            try {
                ticker.join(5_000);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            process.stop();
        }
    }
}
