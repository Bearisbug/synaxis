package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerMoveEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.testing.Env;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The gates send only a player that is online here ({@code docs/DESIGN-M3.md} {@code API-M3-010},
 * {@code REQ-M3-017}; security review B-01): a walk into a frame by a player the player store does
 * not have {@code online} (saving, in transit, still loading, or from the shutdown save on) asks
 * the shard service nothing, so no heartbeat is read, and writes no {@code EVT:transfer_refused}.
 */
class ShardGatesOnlineTest {

    /** A shard service recording whom it was asked to send where; it answers {@code NOT_ONLINE}, as the source side would. */
    private static final class Service implements ShardService {
        final List<String> asked = new CopyOnWriteArrayList<>();

        @Override
        public String shardId() {
            return "r0-c1";
        }

        @Override
        public String regionId() {
            return "r0";
        }

        @Override
        public CompletableFuture<ShardTransferResult> transfer(Player player, String regionId) {
            asked.add(player.getUsername() + " " + regionId);
            return CompletableFuture.completedFuture(ShardTransferResult.NOT_ONLINE);
        }

        @Override
        public CompletableFuture<ShardTransferResult> transferTo(Player player, String shardId) {
            throw new AssertionError("a gate sends a player to a region");
        }
    }

    private final Service service = new Service();
    /** What the gates wrote to the ledger, {@code <event> <attributes>}. */
    private final List<String> ledger = new CopyOnWriteArrayList<>();
    /** The players the player store does not have online. */
    private final Set<Player> notOnline = ConcurrentHashMap.newKeySet();
    private Env env;
    private Instance instance;

    @BeforeEach
    void start() {
        env = Env.create(UnaryOperator.identity());
        instance = env.createFlatInstance();
        new ShardGates(ExampleDirectory.as("r0-c1"), service, (event, attributes) -> ledger.add(event + " " + attributes),
                player -> !notOnline.contains(player)).attach(instance);
    }

    @AfterEach
    void stop() {
        env.close();
    }

    /** What the engine calls once it took a position packet of the player, before it moves it. */
    private void walk(Player player, double x) {
        env.process().eventHandler().call(new PlayerMoveEvent(player, new Pos(x, 41, 1024.5), true));
    }

    @Test
    void aPlayerNotOnlineHereWalksThroughAGateWithoutATrace() {
        final Player ghost = env.createConnection(new GameProfile(UUID.randomUUID(), "Ghost"))
                .connect(instance, new Pos(2024.5, 41, 1024.5));
        notOnline.add(ghost);
        for (int i = 0; i < 100; i++) {
            walk(ghost, 2030);
            walk(ghost, 2040);
        }
        final long refusals = ledger.stream().filter(line -> line.startsWith("transfer_refused ")).count();
        assertEquals("0 transfers, 0 refusals", service.asked.size() + " transfers, " + refusals + " refusals",
                "100 walks into r0-east by a player not online here");

        // Online again while inside the frame: moving on inside it is no walk in, out and in again is
        notOnline.remove(ghost);
        walk(ghost, 2045);
        assertEquals(List.of(), service.asked, "still inside the frame it walked into");
        walk(ghost, 2030);
        walk(ghost, 2040);
        assertEquals(List.of("Ghost r1"), service.asked, "a walk in once online");
    }
}
