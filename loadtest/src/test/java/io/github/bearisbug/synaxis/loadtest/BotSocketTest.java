package io.github.bearisbug.synaxis.loadtest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.bearisbug.synaxis.Auth;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerPreLoginEvent;
import io.github.bearisbug.synaxis.event.player.PlayerChunkLoadEvent;
import io.github.bearisbug.synaxis.event.player.PlayerMoveEvent;
import io.github.bearisbug.synaxis.event.player.PlayerSpawnEvent;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.DisconnectReasons;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
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
import java.util.concurrent.atomic.AtomicLong;
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
    /** Where the servers of the gate tests spawn a player, west of the frame; the second of the round trip a block nearer. */
    private static final Pos SPAWN = new Pos(0.5, 40, 0.5);
    private static final Pos NEARER_SPAWN = new Pos(1.5, 40, 0.5);
    /** How many transfers the servers of the round trip make: there, back and there again. */
    private static final int TRIPS = 3;
    /**
     * How much longer than from the server taking the bot's first position in the frame to the 9th
     * chunk going out a trip the bot timed may be: the way of that position to the server's tick and
     * of the chunk to the bot. A trip timed from the bot's spawn (1.75 s before it reaches the frame)
     * or from the end of its dwell (0.75 s) is longer.
     */
    private static final double SLACK_MILLIS = 500;

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
     * the moment it steps into the frame, the way a shard's gate does, a cookie stored first, three
     * transfers in all. The bot goes back and forth, each time connecting with intent transfer and
     * answering both cookie requests of the new server with the bytes the old one stored, and stays
     * the one bot it logged in as. Its run file has an entry per connection: how it was opened, where
     * the server spawned the bot and the cookie stored on it (§19, the world side of a transfer).
     * <p>
     * The servers send one chunk a tick, so that the 9th chunk of a connection goes out 8 ticks after
     * the first, and note when the one took the bot's first position in the frame and when the other
     * sent the 8th and the 9th chunk. Counted from that position, every trip the bot timed ends after
     * the 8th chunk went out and within {@link #SLACK_MILLIS} of the 9th.
     */
    @Test
    @Timeout(90)
    public void aGateBotFollowsTransfersBackAndForthWithTheCookieItWasGiven(@TempDir Path directory) throws Exception {
        final Seen seen = new Seen();
        try (Server first = new Server("first", transferConfig(), SPAWN);
             Server second = new Server("second", transferConfig(), NEARER_SPAWN)) {
            first.recordArrivals(seen);
            second.recordArrivals(seen);
            first.transferAtTheGate(second, seen, TRIPS);
            second.transferAtTheGate(first, seen, TRIPS);
            final Path out = directory.resolve("bots-0.json");
            final int status = RunCommand.run(new Args(List.of("--port", Integer.toString(first.port()), "--bots", "1",
                    "--profile", "gate", "--gates", GATE, "--dwell", "1s", "--duration", "15s", "--warmup", "3s",
                    "--sample-every", "5s", "--out", out.toString())));
            assertEquals(0, status);
            final JsonObject run = Json.read(out);
            assertEquals(1, run.getAsJsonObject("bots").get("logged_in").getAsInt(), run.toString());
            assertEquals(0, run.getAsJsonObject("bots").get("lost").getAsInt(), run.toString());
            assertEquals(1, Histogram.fromJson(run.getAsJsonObject("login_latency_ms").getAsJsonObject("off")).count(),
                    "a transfer is not another login of the bot");

            final JsonObject transfers = run.getAsJsonObject("transfers");
            assertEquals(TRIPS, transfers.get("completed").getAsLong(), "there, back and there again: " + transfers);
            assertEquals(TRIPS, transfers.get("count").getAsLong(), "no try failed: " + transfers);
            assertEquals(0, transfers.getAsJsonObject("failed_by_reason").size(), transfers.toString());

            // The first login is a login; each later one came from a Transfer and carried that transfer's cookie
            final List<Arrival> arrivals = seen.arrivals;
            assertEquals(TRIPS + 1, arrivals.size(), arrivals.toString());
            assertFalse(arrivals.getFirst().transferred(), "the bot logs in to the first server");
            assertNull(arrivals.getFirst().loginCookie(), "and has no cookie to give yet");
            for (int i = 1; i < arrivals.size(); i++) {
                final Arrival arrival = arrivals.get(i);
                assertTrue(arrival.transferred(), "connection " + i + " has intent transfer");
                assertEquals(i % 2 == 1 ? "second" : "first", arrival.server(), "back and forth");
                assertArrayEquals(seen.cookies.get(i - 1), arrival.loginCookie(), "the cookie asked for during login");
                assertArrayEquals(seen.cookies.get(i - 1), arrival.configurationCookie(),
                        "the cookie asked for during configuration");
            }

            assertTrue(run.has("connections"), "an entry per connection: " + run.keySet());
            final JsonArray connections = run.getAsJsonArray("connections");
            assertEquals(TRIPS + 1, connections.size(), String.valueOf(connections));
            for (int i = 0; i <= TRIPS; i++) {
                final Server server = i % 2 == 0 ? first : second;
                final JsonObject connection = connections.get(i).getAsJsonObject();
                assertEquals(0, connection.get("bot").getAsInt(), connection.toString());
                assertEquals(i + 1, connection.get("connection").getAsInt(), connection.toString());
                assertEquals(i == 0 ? "login" : "transfer", connection.get("intent").getAsString(), connection.toString());
                assertEquals("127.0.0.1:" + server.port(), connection.get("address").getAsString());
                assertPosition(server.spawn, connection, "the first teleport on connection " + (i + 1));
                final JsonObject stored = connection.getAsJsonObject("stored_cookies");
                if (i < TRIPS) {
                    assertEquals(Base64.getEncoder().encodeToString(seen.cookies.get(i)),
                            stored.get(TRANSFER_COOKIE).getAsString(), "the cookie stored before transfer " + (i + 1));
                } else {
                    assertEquals(0, stored.size(), "the last server stored nothing: " + stored);
                }
            }

            // Trip by trip, sorted the same way: from the bot's first position in the frame to the 9th chunk
            final List<Double> millis = values(transfers.getAsJsonObject("transfer_ms"));
            final List<Double> toTheEighth = new ArrayList<>();
            final List<Double> toTheNinth = new ArrayList<>();
            for (int trip = 0; trip < TRIPS; trip++) {
                final long steppedIn = seen.transferredAt.get(trip);
                toTheEighth.add((arrivals.get(trip + 1).eighthChunkAt().get() - steppedIn) / 1e6);
                toTheNinth.add((arrivals.get(trip + 1).ninthChunkAt().get() - steppedIn) / 1e6);
            }
            toTheEighth.sort(null);
            toTheNinth.sort(null);
            for (int trip = 0; trip < TRIPS; trip++) assertTimed(millis.get(trip), toTheEighth.get(trip), toTheNinth.get(trip));
        }
    }

    /**
     * A transfer the next server refuses with {@code synaxis:shard-full}, as a full shard does: the
     * try counts under that reason, and the bot is not lost but logs in again at {@code --host} and
     * {@code --port}, the way a player reconnects, with none of the cookies stored before.
     */
    @Test
    @Timeout(60)
    public void aGateBotWhoseTransferIsRefusedCountsItByReasonAndLogsInAgain(@TempDir Path directory) throws Exception {
        final Seen seen = new Seen();
        try (Server first = new Server("first", transferConfig(), SPAWN);
             Server full = new Server("full", transferConfig(), SPAWN)) {
            first.recordArrivals(seen);
            first.transferAtTheGate(full, seen, 1);
            full.refuseLogins(DisconnectReasons.SHARD_FULL);
            final Path out = directory.resolve("bots-0.json");
            final int status = RunCommand.run(new Args(List.of("--port", Integer.toString(first.port()), "--bots", "1",
                    "--profile", "gate", "--gates", GATE, "--dwell", "1s", "--duration", "8s", "--warmup", "3s",
                    "--sample-every", "4s", "--out", out.toString())));
            assertEquals(0, status);
            final JsonObject run = Json.read(out);
            assertEquals(1, run.getAsJsonObject("bots").get("logged_in").getAsInt(), run.toString());
            assertEquals(0, run.getAsJsonObject("bots").get("lost").getAsInt(), "a refused transfer loses no bot: " + run);
            assertEquals(0, run.getAsJsonObject("kicks_by_reason").size(), run.toString());
            assertEquals(1, Histogram.fromJson(run.getAsJsonObject("login_latency_ms").getAsJsonObject("off")).count(),
                    "logging in again is not another first login");

            final JsonObject transfers = run.getAsJsonObject("transfers");
            assertEquals(1, transfers.get("count").getAsLong(), transfers.toString());
            assertEquals(0, transfers.get("completed").getAsLong(), transfers.toString());
            assertEquals("{\"shard-full\":1}", transfers.getAsJsonObject("failed_by_reason").toString(),
                    "the reason key without its namespace");

            final JsonArray connections = run.getAsJsonArray("connections");
            assertEquals(3, connections.size(), String.valueOf(connections));
            final List<String> opened = new ArrayList<>();
            for (JsonElement connection : connections) {
                final JsonObject entry = connection.getAsJsonObject();
                opened.add(entry.get("intent").getAsString() + " " + entry.get("address").getAsString());
            }
            assertEquals(List.of("login 127.0.0.1:" + first.port(), "transfer 127.0.0.1:" + full.port(),
                    "login 127.0.0.1:" + first.port()), opened);
            assertPosition(SPAWN, connections.get(0).getAsJsonObject(), "spawned on the first server");
            assertFalse(connections.get(1).getAsJsonObject().has("first_position"), "never spawned on the full one");
            assertPosition(SPAWN, connections.get(2).getAsJsonObject(), "spawned on the first server again");

            assertEquals(2, seen.arrivals.size(), seen.arrivals.toString());
            final Arrival again = seen.arrivals.get(1);
            assertFalse(again.transferred(), "logged in again, not transferred");
            assertNull(again.loginCookie(), "with no cookie from before");
            assertNull(again.configurationCookie());
        }
    }

    /**
     * A gate bot the server has back in configuration when its walk reaches the frame sends no
     * position there: its try starts with the first position in the frame it sends once it plays
     * again. The server sends it on a second after the configuration ended, so the trip it times
     * starts after that end and before the Transfer went out, not some 1.5 s earlier where the walk
     * crossed the edge of the frame.
     */
    @Test
    @Timeout(60)
    public void aGateBotInConfigurationAtTheFrameTimesItsTryFromTheFirstPositionItSends() throws Exception {
        final Seen seen = new Seen();
        final BotStats stats = new BotStats();
        final AtomicLong configuredUntil = new AtomicLong();
        try (Server first = new Server("first", transferConfig(), SPAWN);
             Server second = new Server("second", transferConfig(), SPAWN)) {
            second.recordArrivals(seen);
            // In configuration from 0.2 s after the spawn to past 2.7 s: the walk reaches the frame at 1.25 s
            first.reconfigureThenTransfer(200, 2_500, 1_000, second, seen, configuredUntil);
            final Bot bot = new Bot(0, new Bot.Settings("127.0.0.1", first.port(), Profile.GATE, false, 9f, false, 3,
                    30_000, List.of(gate()), 0.5, Bot.NO_TRANSFER_NANOS), stats);
            final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            final Thread thread = Thread.ofVirtual().start(() -> bot.run(end));
            thread.join(20_000);
            bot.close();
        }
        assertTrue(configuredUntil.get() != 0, "the bot was taken back to configuration");
        assertEquals(1, stats.transferMillis.count(), "the trip was timed: " + stats.transfers());
        final double millis = values(stats.transferMillis.toJson()).getFirst();
        final Arrival arrival = seen.arrivals.getFirst();
        final double sinceTheTransfer = (arrival.eighthChunkAt().get() - seen.transferredAt.getFirst()) / 1e6;
        assertTrue(millis >= sinceTheTransfer - 0.1, "a trip of " + millis + " ms started after the Transfer went out: "
                + "the 8th chunk went out " + sinceTheTransfer + " ms after it");
        final double sinceTheConfiguration = (arrival.ninthChunkAt().get() - configuredUntil.get()) / 1e6;
        assertTrue(millis <= sinceTheConfiguration + SLACK_MILLIS, "a trip of " + millis + " ms started in the "
                + "configuration: the 9th chunk went out " + sinceTheConfiguration + " ms after it ended");
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

    /**
     * The servers of the gate tests: the dispatcher threads and thread provider the build passes in
     * (the strict rounds of the CI, as the engine's test environment takes them), transfers accepted,
     * and one chunk a tick, so that the 9th chunk of a connection goes out 8 ticks after the first.
     */
    private static ServerConfig transferConfig() {
        final ServerConfig jvm = ServerConfig.fromSystemProperties();
        return ServerConfig.builder().dispatcherThreads(jvm.dispatcherThreads()).threadProvider(jvm.threadProvider())
                .connectionGateTrustedNetworks("127.0.0.0/8").chunkViewDistance(4).acceptTransfers(true)
                .maxChunksPerTick(1).build();
    }

    private static Profile.Frame gate() {
        return new Args(List.of("--gates", GATE)).gates("gates").getFirst();
    }

    /** Whether the first teleport of a connection entry is {@code expected}. */
    private static void assertPosition(Pos expected, JsonObject connection, String message) {
        final JsonArray position = connection.getAsJsonArray("first_position");
        assertTrue(position != null && position.size() == 3, message + ": " + connection);
        assertEquals(expected.x(), position.get(0).getAsDouble(), message);
        assertEquals(expected.y(), position.get(1).getAsDouble(), message);
        assertEquals(expected.z(), position.get(2).getAsDouble(), message);
    }

    /**
     * Whether a trip the bot timed ends where it should on the servers' clock, the same one in this
     * JVM: after the 8th chunk of the new connection went out, and within {@link #SLACK_MILLIS} of the
     * 9th, both counted from the server taking the bot's first position in the frame. The bot starts
     * its clock before that position goes out, and the 9th chunk reaches it a tick after the 8th.
     */
    private static void assertTimed(double millis, double toTheEighth, double toTheNinth) {
        // 0.1 ms: the resolution of the run file
        assertTrue(millis >= toTheEighth - 0.1, "a trip of " + millis + " ms ended before the 9th chunk: the 8th went out "
                + toTheEighth + " ms after the first position in the frame");
        assertTrue(millis <= toTheNinth + SLACK_MILLIS, "a trip of " + millis + " ms started before the first position "
                + "in the frame: the 9th chunk went out " + toTheNinth + " ms after it");
    }

    /** The values of a histogram of the run file, smallest first. */
    private static List<Double> values(JsonObject histogram) {
        final double resolution = histogram.get("resolution").getAsDouble();
        final List<Double> values = new ArrayList<>();
        for (Map.Entry<String, JsonElement> bucket : histogram.getAsJsonObject("buckets").entrySet())
            for (long i = 0; i < bucket.getValue().getAsLong(); i++)
                values.add(Histogram.round(Long.parseLong(bucket.getKey()) * resolution));
        values.sort(null);
        return values;
    }

    /** What the servers of a gate test saw, in the order it happened. */
    private static final class Seen {
        /**
         * The cookie each transfer stored, and when its server sent it, {@link System#nanoTime()}: at a
         * gate, the moment it took the bot's first position in the frame.
         */
        final List<byte[]> cookies = new CopyOnWriteArrayList<>();
        final List<Long> transferredAt = new CopyOnWriteArrayList<>();
        final List<Arrival> arrivals = new CopyOnWriteArrayList<>();
    }

    /**
     * A login seen by a test server: whether it came from a Transfer, the cookie it gave in each state,
     * and when the 8th and the 9th chunk of the connection went out, {@link System#nanoTime()}.
     */
    private record Arrival(String server, boolean transferred, byte @Nullable [] loginCookie,
                           byte @Nullable [] configurationCookie, AtomicLong eighthChunkAt, AtomicLong ninthChunkAt) {
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

    /** A really ticking server on loopback: flat stone up to y 40, every player spawning at (0.5, 40, 0.5) unless told otherwise. */
    static final class Server implements AutoCloseable {
        final ServerProcess process;
        final String name;
        final Pos spawn;
        private final Thread ticker;

        Server(String name, ServerConfig config) {
            this(name, new Auth.Offline(false), config, SPAWN);
        }

        Server(String name, ServerConfig config, Pos spawn) {
            this(name, new Auth.Offline(false), config, spawn);
        }

        Server(String name, Auth auth, ServerConfig config) {
            this(name, auth, config, SPAWN);
        }

        Server(String name, Auth auth, ServerConfig config, Pos spawn) {
            this.name = name;
            this.spawn = spawn;
            this.process = ServerProcess.of(auth, config);
            final InstanceContainer world = process.instance().createInstanceContainer();
            world.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
            process.eventHandler().addListener(AsyncPlayerConfigurationEvent.class, event -> {
                event.setSpawningInstance(world);
                event.getPlayer().setRespawnPoint(spawn);
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

        /**
         * Asks every login for the transfer cookie, during login and again during configuration, and
         * notes when the 8th and the 9th chunk of the connection go out.
         */
        void recordArrivals(Seen seen) {
            final Map<PlayerConnection, Optional<byte[]>> duringLogin = new ConcurrentHashMap<>();
            final Map<PlayerConnection, Arrival> arrivals = new ConcurrentHashMap<>();
            final Map<PlayerConnection, AtomicInteger> chunks = new ConcurrentHashMap<>();
            process.eventHandler().addListener(AsyncPlayerPreLoginEvent.class, event ->
                    duringLogin.put(event.getConnection(), Optional.ofNullable(cookie(event.getConnection()))));
            process.eventHandler().addListener(AsyncPlayerConfigurationEvent.class, event -> {
                if (!event.isFirstConfig()) return;
                final PlayerConnection connection = event.getPlayer().getPlayerConnection();
                final byte[] login = duringLogin.remove(connection).orElse(null);
                final Arrival arrival = new Arrival(name, connection.wasTransferred(), login, cookie(connection),
                        new AtomicLong(), new AtomicLong());
                arrivals.put(connection, arrival);
                seen.arrivals.add(arrival);
            });
            process.eventHandler().addListener(PlayerChunkLoadEvent.class, event -> {
                final long now = System.nanoTime();
                final PlayerConnection connection = event.getPlayer().getPlayerConnection();
                final Arrival arrival = arrivals.get(connection);
                if (arrival == null) return;
                final int sent = chunks.computeIfAbsent(connection, _ -> new AtomicInteger()).incrementAndGet();
                if (sent == 8) arrival.eighthChunkAt().set(now);
                if (sent == 9) arrival.ninthChunkAt().set(now);
            });
        }

        /**
         * Sends a player that steps into the frame to {@code other}, once per connection and while the
         * servers of the test made fewer than {@code transfers}: a cookie of its own first, then the
         * Transfer, as a shard's gate does.
         */
        void transferAtTheGate(Server other, Seen seen, int transfers) {
            final Profile.Frame gate = gate();
            final Set<Player> sent = ConcurrentHashMap.newKeySet();
            process.eventHandler().addListener(PlayerMoveEvent.class, event -> {
                final long now = System.nanoTime();
                final Pos to = event.getNewPosition();
                if (!gate.contains(to.x(), to.y(), to.z()) || seen.cookies.size() >= transfers
                        || !sent.add(event.getPlayer())) return;
                final byte[] cookie = ("ticket " + seen.cookies.size() + " from " + name).getBytes(StandardCharsets.UTF_8);
                seen.transferredAt.add(now);
                seen.cookies.add(cookie);
                final PlayerConnection connection = event.getPlayer().getPlayerConnection();
                connection.storeCookie(TRANSFER_COOKIE, cookie);
                connection.transfer("127.0.0.1", other.port());
            });
        }

        /** Refuses every login with {@code reason}, as a full shard does. */
        void refuseLogins(Component reason) {
            process.eventHandler().addListener(AsyncPlayerPreLoginEvent.class,
                    event -> event.getConnection().kick(reason));
        }

        /**
         * Takes a player back to configuration {@code afterMillis} after its first spawn, holds it
         * there {@code holdMillis}, notes the end in {@code configuredUntil} and sends it on to
         * {@code other} {@code transferMillis} later, a cookie stored first.
         * <p>
         * The engine takes a player out of play, and spawns it again after the configuration, from the
         * ticker thread, where with assertions on the ownership check of a player a tick thread owns
         * refuses both ({@code Player#remove}, {@code Player#UNSAFE_init}). So the step out of play is
         * drained on the player's own tick thread here, and the Transfer goes out on the connection
         * without waiting for a position in the frame the unspawned player would never report.
         */
        void reconfigureThenTransfer(long afterMillis, long holdMillis, long transferMillis, Server other, Seen seen,
                                     AtomicLong configuredUntil) {
            final Set<Player> reconfigured = ConcurrentHashMap.newKeySet();
            process.eventHandler().addListener(PlayerSpawnEvent.class, event -> {
                final Player player = event.getPlayer();
                if (!reconfigured.add(player)) return;
                player.scheduler().buildTask(() -> {
                    player.startConfigurationPhase();
                    process.connection().updateWaitingPlayers();
                }).delay(TaskSchedule.millis(afterMillis)).schedule();
            });
            process.eventHandler().addListener(AsyncPlayerConfigurationEvent.class, event -> {
                if (event.isFirstConfig()) return;
                final PlayerConnection connection = event.getPlayer().getPlayerConnection();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(holdMillis));
                configuredUntil.set(System.nanoTime());
                Thread.ofVirtual().start(() -> {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(transferMillis));
                    final byte[] cookie = ("ticket 0 from " + name).getBytes(StandardCharsets.UTF_8);
                    seen.transferredAt.add(System.nanoTime());
                    seen.cookies.add(cookie);
                    connection.storeCookie(TRANSFER_COOKIE, cookie);
                    connection.transfer("127.0.0.1", other.port());
                });
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
