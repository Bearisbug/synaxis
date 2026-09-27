package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.app.transfer.TransferOutcome;
import io.github.bearisbug.synaxis.app.transfer.TransferTickets;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.testing.Env;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static io.github.bearisbug.synaxis.app.shard.ShardTransferResult.DIRECTORY_UNAVAILABLE;
import static io.github.bearisbug.synaxis.app.shard.ShardTransferResult.FULL;
import static io.github.bearisbug.synaxis.app.shard.ShardTransferResult.OFFLINE;
import static io.github.bearisbug.synaxis.app.shard.ShardTransferResult.SAME_TARGET;
import static io.github.bearisbug.synaxis.app.shard.ShardTransferResult.SENT;
import static io.github.bearisbug.synaxis.app.shard.ShardTransferResult.UNKNOWN_TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The choice of a channel and the results of {@link ShardService} ({@code docs/DESIGN-M3.md}
 * {@code API-M3-010}, {@code REQ-M3-017}): the checks in their order, the first one failing
 * deciding, and {@link TransferTickets} only reached once a channel is chosen.
 */
class ShardServiceImplTest {

    /** A heartbeat reader answering what the test sets, recording what it is asked. */
    private static final class Heartbeats implements HeartbeatReader {
        final List<String> asked = new CopyOnWriteArrayList<>();
        volatile Supplier<CompletableFuture<List<HeartbeatRow>>> region = CompletableFuture::new;
        volatile Supplier<CompletableFuture<@Nullable HeartbeatRow>> shard = CompletableFuture::new;

        @Override
        public CompletableFuture<List<HeartbeatRow>> region(String mapId, String regionId) {
            asked.add("region " + mapId + " " + regionId);
            return region.get();
        }

        @Override
        public CompletableFuture<@Nullable HeartbeatRow> shard(String mapId, String shardId) {
            asked.add("shard " + mapId + " " + shardId);
            return shard.get();
        }
    }

    /** The source side of a transfer, recording where it was asked to send the player. */
    private static final class Tickets implements TransferTickets {
        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile CompletableFuture<TransferOutcome> outcome = CompletableFuture.completedFuture(TransferOutcome.SENT);

        @Override
        public CompletableFuture<TransferOutcome> transfer(Player player, String targetShardId, String host, int port) {
            sent.add(targetShardId + " " + host + ":" + port);
            return outcome;
        }
    }

    private final Heartbeats heartbeats = new Heartbeats();
    private final Tickets tickets = new Tickets();
    private final ShardService service = new ShardServiceImpl(ExampleDirectory.as("r0-c1"), heartbeats, tickets);
    private Env env;

    private static HeartbeatRow row(String shardId, int port, int online, int capacity, boolean accepting, boolean live) {
        return new HeartbeatRow(shardId, shardId.substring(0, 2), Integer.parseInt(shardId.substring(4)), "127.0.0.1",
                port, online, capacity, accepting, live);
    }

    private static <T> Supplier<CompletableFuture<T>> answer(T value) {
        return () -> CompletableFuture.completedFuture(value);
    }

    private static ShardTransferResult result(CompletableFuture<ShardTransferResult> transfer) throws Exception {
        return transfer.get(5, TimeUnit.SECONDS);
    }

    @BeforeEach
    void start() {
        env = Env.create(UnaryOperator.identity());
    }

    @AfterEach
    void stop() {
        env.process().stop();
    }

    private Player player() {
        return env.createPlayer(env.createFlatInstance(), new Pos(0, 42, 0));
    }

    @Test
    void itNamesThisShardAndItsRegion() {
        assertEquals("r0-c1", service.shardId());
        assertEquals("r0", service.regionId());
    }

    @Test
    void theLeastLoadedChannelIsChosenAndATieGoesToTheSmallestShardId() throws Exception {
        final Player player = player();
        heartbeats.region = answer(List.of(row("r1-c3", 25613, 100, 200, true, true),
                row("r1-c2", 25612, 100, 200, true, true), row("r1-c1", 25611, 60, 100, true, true)));

        assertEquals(SENT, result(service.transfer(player, "r1")));
        assertEquals(List.of("region synth r1"), heartbeats.asked, "the rows of the target region of this map");
        assertEquals(List.of("r1-c2 127.0.0.1:25612"), tickets.sent,
                "0.5 beats 0.6, and of the two at 0.5 the smaller shard id, with the address of its row");

        heartbeats.region = answer(List.of(row("r1-c3", 25613, 200, 200, true, true),
                row("r1-c2", 25612, 200, 200, true, true), row("r1-c1", 25611, 100, 100, true, true)));
        assertEquals(FULL, result(service.transfer(player, "r1")));
        assertEquals(1, tickets.sent.size(), "no transfer to a full region");
    }

    @Test
    void aChannelThatIsNotLiveOrNotAcceptingIsNeverChosen() throws Exception {
        final Player player = player();
        final HeartbeatRow expired = row("r1-c1", 25611, 0, 200, true, false);
        final HeartbeatRow closed = row("r1-c2", 25612, 0, 200, false, true);
        heartbeats.region = answer(List.of(expired, closed, row("r1-c3", 25613, 120, 200, true, true)));
        assertEquals(SENT, result(service.transfer(player, "r1")));
        assertEquals(List.of("r1-c3 127.0.0.1:25613"), tickets.sent, "the only live and accepting channel, loaded as it is");

        heartbeats.region = answer(List.of(expired, closed));
        assertEquals(OFFLINE, result(service.transfer(player, "r1")), "empty seats of channels that do not take anyone");
        heartbeats.region = answer(List.of(expired, closed, row("r1-c3", 25613, 200, 200, true, true)));
        assertEquals(FULL, result(service.transfer(player, "r1")), "what takes players is full");
        assertEquals(1, tickets.sent.size());
    }

    @Test
    void everyCheckDecidesInItsOrderAndOnlyAChosenChannelReachesTheTickets() throws Exception {
        final Player player = player();
        assertEquals(SAME_TARGET, result(service.transfer(player, "r0")));
        assertEquals(UNKNOWN_TARGET, result(service.transfer(player, "r9")));
        assertEquals(SAME_TARGET, result(service.transferTo(player, "r0-c1")));
        assertEquals(List.of(), heartbeats.asked, "how the target is written is judged on the directory alone");

        heartbeats.region = () -> CompletableFuture.failedFuture(new SQLException("the database is down"));
        assertEquals(DIRECTORY_UNAVAILABLE, result(service.transfer(player, "r1")));
        heartbeats.region = () -> CompletableFuture.supplyAsync(() -> List.of(row("r1-c1", 25602, 0, 200, true, true)),
                CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS));
        final long start = System.nanoTime();
        assertEquals(DIRECTORY_UNAVAILABLE, result(service.transfer(player, "r1")));
        final long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(waited >= 2_000 && waited < 2_900, "the service gives up on its own after 2 s, not 3: " + waited + " ms");
        heartbeats.region = answer(List.of());
        assertEquals(OFFLINE, result(service.transfer(player, "r1")));
        heartbeats.region = answer(List.of(row("r1-c1", 25602, 200, 200, true, true), row("r1-c2", 25603, 150, 150, true, true)));
        assertEquals(FULL, result(service.transfer(player, "r1")));

        heartbeats.shard = answer(null);
        assertEquals(UNKNOWN_TARGET, result(service.transferTo(player, "r1-c9")));
        heartbeats.shard = answer(row("r1-c1", 25602, 0, 200, true, false));
        assertEquals(OFFLINE, result(service.transferTo(player, "r1-c1")));
        heartbeats.shard = answer(row("r1-c1", 25602, 0, 200, false, true));
        assertEquals(OFFLINE, result(service.transferTo(player, "r1-c1")));
        heartbeats.shard = answer(row("r1-c1", 25602, 200, 200, true, true));
        assertEquals(FULL, result(service.transferTo(player, "r1-c1")));
        assertEquals(List.of("shard synth r1-c9", "shard synth r1-c1", "shard synth r1-c1", "shard synth r1-c1"),
                heartbeats.asked.stream().filter(asked -> asked.startsWith("shard ")).toList());
        assertEquals(List.of(), tickets.sent, "no check before the tickets touched the player");
        assertFalse(player.isRemoved(), "the player is still here");

        heartbeats.region = answer(List.of(row("r1-c1", 25602, 10, 200, true, true)));
        for (TransferOutcome outcome : TransferOutcome.values()) {
            tickets.outcome = CompletableFuture.completedFuture(outcome);
            assertEquals(ShardTransferResult.valueOf(outcome.name()), result(service.transfer(player, "r1")), outcome.name());
        }
        assertEquals(List.of("r1-c1 127.0.0.1:25602", "r1-c1 127.0.0.1:25602", "r1-c1 127.0.0.1:25602",
                "r1-c1 127.0.0.1:25602"), tickets.sent);
    }

    @Test
    void anotherChannelOfThisRegionCanBeTheTargetOfTransferTo() throws Exception {
        final Player player = player();
        heartbeats.shard = answer(row("r0-c2", 25604, 199, 200, true, true));
        assertEquals(SENT, result(service.transferTo(player, "r0-c2")));
        assertEquals(List.of("r0-c2 127.0.0.1:25604"), tickets.sent);
    }

    @Test
    void aTransferThatFailsExceptionallyFailsTheServiceWithTheSameCause() {
        final Player player = player();
        heartbeats.region = answer(List.of(row("r1-c1", 25602, 10, 200, true, true)));
        tickets.outcome = CompletableFuture.failedFuture(new IllegalStateException("the ticket does not encode"));
        final ExecutionException failed = assertThrows(ExecutionException.class,
                () -> service.transfer(player, "r1").get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failed.getCause());
        assertEquals("the ticket does not encode", failed.getCause().getMessage());
    }
}
