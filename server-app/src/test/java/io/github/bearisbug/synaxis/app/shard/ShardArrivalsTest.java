package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.app.transfer.TransferTicket;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerChunkLoadEvent;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.testing.Env;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The arrival of a transferred player ({@code docs/DESIGN-M3.md} {@code API-M3-010},
 * {@code REQ-M3-017}): the spawn at the arrival of the gate back to where it came from, or the
 * region's spawn without one, and {@code EVT:transfer_completed} once its 9th chunk is sent.
 */
class ShardArrivalsTest {

    /** Where {@code serveMap} spawns every player: the spawn of the region, whatever it is here. */
    private static final Pos REGION_SPAWN = new Pos(1016.5, 41, 1016.5);
    private static final long ISSUED_AT = 1_790_000_000_000L;

    private final List<String> ledger = new CopyOnWriteArrayList<>();
    private final AtomicLong clock = new AtomicLong(ISSUED_AT + 400);
    private Env env;
    private Instance instance;

    @BeforeEach
    void start() {
        env = Env.create(UnaryOperator.identity());
        instance = env.createFlatInstance();
    }

    @AfterEach
    void stop() {
        env.process().stop();
    }

    private ShardArrivals arrivals(ShardDirectory directory) {
        final ShardArrivals arrivals = new ShardArrivals(directory, (event, attributes) -> ledger.add(event + " " + attributes),
                clock::get);
        arrivals.attach(instance);
        return arrivals;
    }

    private Player player() {
        return env.createPlayer(instance, REGION_SPAWN);
    }

    private static TransferTicket ticket(Player player, String source, String target, int seed) {
        final byte[] nonce = new byte[16];
        nonce[0] = (byte) seed;
        nonce[3] = (byte) 0xAB;
        return new TransferTicket(7, player.getUuid(), 1, source, target, 3, nonce, ISSUED_AT,
                ISSUED_AT + TransferTicket.LIFETIME_MS);
    }

    /** What the engine calls each time it puts a chunk of the player's into the connection. */
    private void chunksSent(Player player, int count) {
        for (int i = 0; i < count; i++)
            env.process().eventHandler().call(new PlayerChunkLoadEvent(player, 126 + i % 3, 63 + i / 3));
    }

    private static String completed(String ref, long firstBatchMillis, String source, String target) {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("transfer_ref", ref);
        attributes.put("first_batch_ms", firstBatchMillis);
        attributes.put("source_shard", source);
        attributes.put("target_shard", target);
        return "transfer_completed " + attributes;
    }

    @Test
    void aPlayerComingThroughAGateSpawnsAtTheArrivalOfTheGateBack() {
        final ShardArrivals arrivals = arrivals(ExampleDirectory.as("r1-c1"));
        final Player player = player();
        arrivals.claimed(player, ticket(player, "r0-c1", "r1-c1", 1));
        assertEquals(ExampleDirectory.R1_WEST.arrival(), player.getRespawnPoint(), "r1-west leads back to r0");
    }

    @Test
    void withoutAGateBackThePlayerKeepsTheRegionSpawnAndTheArrivalIsStillCounted() {
        final ShardDirectory.Region r2 = new ShardDirectory.Region("r2", new SynxManifest.Bounds(0, 128, 127, 255),
                List.of(new ShardDirectory.Gate("r2-east", new ShardDirectory.Frame(2032, 0, 3000, 2047, 255, 3015), "r1",
                        new Pos(2024.5, 200.0, 3007.5))));
        final ShardDirectory directory = new ShardDirectory("synth", List.of(ExampleDirectory.R0, ExampleDirectory.R1, r2),
                List.of(new ShardDirectory.Channel("r2-c1", "r2", 1, "127.0.0.1", 25603, 200)), "r2-c1");
        final ShardArrivals arrivals = arrivals(directory);
        final Player player = player();
        arrivals.claimed(player, ticket(player, "r0-c1", "r2-c1", 1));
        assertEquals(REGION_SPAWN, player.getRespawnPoint(), "r2 has no gate to r0");

        clock.set(ISSUED_AT + 1_234);
        chunksSent(player, 9);
        assertEquals(List.of(completed("010000ab", 1_234, "r0-c1", "r2-c1")), ledger);
    }

    @Test
    void theNinthChunkWritesTransferCompletedOnce() {
        final ShardArrivals arrivals = arrivals(ExampleDirectory.as("r1-c1"));
        final Player player = player();
        arrivals.claimed(player, ticket(player, "r0-c1", "r1-c1", 2));
        chunksSent(player, 8);
        assertEquals(List.of(), ledger, "8 chunks are not the first batch");

        clock.set(ISSUED_AT + 2_345);
        chunksSent(player, 1);
        assertEquals(List.of(completed("020000ab", 2_345, "r0-c1", "r1-c1")), ledger);
        chunksSent(player, 20);
        assertEquals(1, ledger.size(), "once per arrival");

        final Player stranger = player();
        chunksSent(stranger, 9);
        assertEquals(1, ledger.size(), "a player that did not come with a ticket is not counted");
    }

    @Test
    void aPlayerLeavingBeforeItsNinthChunkWritesNothing() {
        final ShardArrivals arrivals = arrivals(ExampleDirectory.as("r1-c1"));
        final Player leaving = player();
        arrivals.claimed(leaving, ticket(leaving, "r0-c1", "r1-c1", 3));
        chunksSent(leaving, 5);
        env.process().eventHandler().call(new PlayerDisconnectEvent(leaving));
        chunksSent(leaving, 4);
        assertEquals(List.of(), ledger, "a transfer that did not complete");

        final Player staying = player();
        arrivals.claimed(staying, ticket(staying, "r0-c1", "r1-c1", 4));
        chunksSent(staying, 9);
        assertEquals(List.of(completed("040000ab", 400, "r0-c1", "r1-c1")), ledger);
    }

    @Test
    void anArrivalNotCompletedWithin60sIsDroppedAtTheNextClaim() {
        final ShardArrivals arrivals = arrivals(ExampleDirectory.as("r1-c1"));
        final Player stuck = player();
        arrivals.claimed(stuck, ticket(stuck, "r0-c1", "r1-c1", 5));
        chunksSent(stuck, 3);

        clock.addAndGet(60_001);
        final Player next = player();
        arrivals.claimed(next, ticket(next, "r0-c1", "r1-c1", 6));
        chunksSent(stuck, 6);
        assertEquals(List.of(), ledger, "the stuck arrival was dropped without an event");
        chunksSent(next, 9);
        assertEquals(List.of(completed("060000ab", 60_401, "r0-c1", "r1-c1")), ledger);
    }
}
