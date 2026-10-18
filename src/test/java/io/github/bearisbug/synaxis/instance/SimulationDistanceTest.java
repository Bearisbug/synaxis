package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.DormancyPolicy;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityProjectile;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityPositionSyncPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SpawnEntityPacket;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.TestConnection;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The simulation distance of an instance ({@code docs/DESIGN-M3.md} {@code REQ-M3-009},
 * {@code API-M3-006}, {@code ADR-M3-017}): outside it chunks and entities stay loaded, sent and
 * visible, but only the entity schedulers run.
 * <p>
 * A process of its own: the entity view distance has to reach the entity 6 chunks away, and the
 * view distance of the §19 scenarios is 12.
 */
public class SimulationDistanceTest {

    private static final Pos PLAYER = new Pos(0.5, 42, 0.5);
    /** 6 chunks from the player along x, outside a simulation distance of 4. */
    private static final Pos FAR = new Pos(6 * 16 + 8.5, 42, 0.5);

    private Env env;

    /** Counts the ticks it runs the body of. */
    static final class Counting extends Entity {
        final AtomicInteger updates = new AtomicInteger();

        Counting() {
            super(EntityType.ZOMBIE);
            setNoGravity(true);
        }

        @Override
        public void update(long time) {
            updates.incrementAndGet();
        }
    }

    /** A block handler counting its ticks. */
    static final class TickCounter implements BlockHandler {
        final AtomicInteger ticks = new AtomicInteger();

        @Override
        public Key getKey() {
            return Key.key("test:tick_counter");
        }

        @Override
        public boolean isTickable() {
            return true;
        }

        @Override
        public void tick(Tick tick) {
            ticks.incrementAndGet();
        }
    }

    @BeforeEach
    void setUp() {
        env = Env.create(settings -> settings.entityViewDistance(8).chunkViewDistance(12));
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    private static Counting spawn(Instance instance, Pos at) {
        final Counting entity = new Counting();
        entity.setInstance(instance, at).join();
        return entity;
    }

    private void ticks(int count) {
        for (int i = 0; i < count; i++) env.tick();
    }

    /** The §19 scenario: an entity and a ticking block 6 chunks from the only player, simulation distance 4. */
    @Test
    public void aChunkOutsideTheSimulationDistanceDoesNotTickButIsStillSent() {
        final Instance instance = env.createFlatInstance();
        instance.setSimulationDistance(4);
        final TestConnection connection = env.createConnection();
        final Collector<ChunkDataPacket> chunks = connection.trackIncoming(ChunkDataPacket.class);
        final Collector<SpawnEntityPacket> spawns = connection.trackIncoming(SpawnEntityPacket.class);
        final Player player = connection.connect(instance, PLAYER);
        final TickCounter handler = new TickCounter();
        instance.setBlock(6 * 16 + 1, 41, 1, Block.STONE.withHandler(handler));
        final Counting entity = spawn(instance, FAR);
        final long aliveTicks = entity.getAliveTicks();

        ticks(100);

        assertEquals(aliveTicks, entity.getAliveTicks(), "the entity body does not run outside the simulation distance");
        assertEquals(0, entity.updates.get());
        assertEquals(0, handler.ticks.get(), "the block handler does not tick outside the simulation distance");
        assertFalse(instance.isChunkSimulated(6, 0));
        assertTrue(instance.isChunkSimulated(4, 4), "Chebyshev distance 4 is still simulated");
        assertTrue(instance.isChunkSimulated(player.getPosition().chunkX(), player.getPosition().chunkZ()));
        assertTrue(chunks.collect().stream().anyMatch(packet -> packet.chunkX() == 6 && packet.chunkZ() == 0),
                "the chunk is still sent");
        assertTrue(spawns.collect().stream().anyMatch(packet -> packet.entityId() == entity.getEntityId()),
                "the entity is still visible");
    }

    @Test
    public void theSchedulerOfAnEntityOutsideTheSimulationDistanceRuns() {
        final Instance instance = env.createFlatInstance();
        instance.setSimulationDistance(4);
        env.createPlayer(instance, PLAYER);
        final Counting entity = spawn(instance, FAR);
        env.tick();
        final long aliveTicks = entity.getAliveTicks();
        final AtomicInteger tasks = new AtomicInteger();
        final AtomicReference<CompletableFuture<Void>> teleport = new AtomicReference<>();
        entity.scheduleNextTick(_ -> tasks.incrementAndGet());
        entity.scheduleNextTick(owned -> teleport.set(owned.teleport(FAR.add(3, 0, 3))));

        ticks(2);

        assertEquals(1, tasks.get(), "a task scheduled on the entity runs once");
        assertTrue(teleport.get().isDone(), "the teleport continues");
        assertEquals(FAR.add(3, 0, 3), entity.getPosition());
        assertEquals(aliveTicks, entity.getAliveTicks(), "still no tick body");
    }

    @Test
    public void comingBackIntoTheSimulationDistanceSynchronizesThePositionOnce() {
        final Instance instance = env.createFlatInstance();
        instance.setSimulationDistance(4);
        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(instance, PLAYER);
        final Counting entity = spawn(instance, FAR);
        ticks(200);
        assertEquals(0, entity.updates.get());

        player.acquirable().sync(owned -> owned.teleport(new Pos(3 * 16 + 8.5, 42, 0.5)).join());
        final Collector<EntityPositionSyncPacket> syncs = connection.trackIncoming(EntityPositionSyncPacket.class);
        env.tick();

        assertEquals(1, entity.updates.get(), "3 chunks away the entity runs again");
        assertEquals(1, syncs.collect().stream().filter(packet -> packet.entityId() == entity.getEntityId()).count(),
                "the first tick back synchronizes the position");
    }

    /** The §19 scenario: 100 ticks out of the simulation distance, then 100 ticks 40 blocks from the player. */
    @Test
    public void theSimulationDistanceComesBeforeTheDormancyPolicy() {
        final Instance instance = env.createFlatInstance();
        instance.setSimulationDistance(4);
        env.createPlayer(instance, PLAYER);
        // The policy gets the player positions from the tick after they were first asked for
        final var _ = instance.playerPositions();
        final Counting entity = spawn(instance, FAR);
        final AtomicInteger asked = new AtomicInteger();
        final DormancyPolicy farAfter32 = (_, distance) -> {
            asked.incrementAndGet();
            return distance > 32 ? 20 : 1;
        };
        entity.setDormancyPolicy(farAfter32);
        env.tick();
        final long outside = entity.getAliveTicks();

        ticks(100);
        assertEquals(outside, entity.getAliveTicks(), "no tick body outside the simulation distance");
        assertEquals(0, asked.get(), "the policy is not asked outside the simulation distance");

        entity.acquirable().sync(owned -> owned.teleport(new Pos(40.5, 42, 0.5)).join());
        final long inside = entity.getAliveTicks();
        ticks(100);
        assertEquals(inside + 5, entity.getAliveTicks(), "40 blocks away the policy has it run every 20 ticks");
        assertTrue(asked.get() > 0);
    }

    /** What the M2 match runs with: no simulation distance, no automatic unload. */
    @Test
    public void theEngineDefaultsSimulateEveryChunkAndUnloadNothing() {
        final Instance instance = env.createFlatInstance();
        assertEquals(-1, instance.simulationDistance());
        assertEquals(-1, instance.chunkUnloadGraceTicks());
        env.createPlayer(instance, PLAYER);
        instance.loadChunk(20, 0).join();
        final Counting entity = spawn(instance, new Pos(20 * 16 + 8.5, 42, 0.5));
        final int loaded = instance.getChunks().size();

        ticks(200);

        assertEquals(200, entity.updates.get(), "20 chunks from every player the entity runs every tick");
        assertTrue(instance.isChunkSimulated(20, 0));
        assertTrue(instance.isChunkSimulated(1_000, -1_000));
        assertEquals(loaded, instance.getChunks().size(), "no chunk is unloaded");
    }

    @Test
    public void aSimulationDistanceBelowOneOtherThanUnlimitedIsRefused() {
        final Instance instance = env.createFlatInstance();
        instance.setSimulationDistance(4);
        assertThrows(IllegalArgumentException.class, () -> instance.setSimulationDistance(0));
        assertThrows(IllegalArgumentException.class, () -> instance.setSimulationDistance(-2));
        assertEquals(4, instance.simulationDistance());
        instance.setSimulationDistance(-1);
        assertEquals(-1, instance.simulationDistance());
    }

    @Test
    public void aSimulationDistanceTakesEffectOnTheNextTickAndCanBeLifted() {
        final Instance instance = env.createFlatInstance();
        env.createPlayer(instance, PLAYER);
        final Counting entity = spawn(instance, FAR);
        instance.setSimulationDistance(4);
        assertTrue(instance.isChunkSimulated(6, 0), "until the next tick every chunk is simulated");
        env.tick();
        assertFalse(instance.isChunkSimulated(6, 0));

        instance.setSimulationDistance(-1);
        entity.updates.set(0);
        ticks(10);
        assertEquals(10, entity.updates.get());
        assertTrue(instance.isChunkSimulated(6, 0));
    }

    /**
     * At most 32, the largest view distance of a player: the chunks a tick simulates are (2d + 1)² per
     * player, and a distance near {@link Integer#MAX_VALUE} never ends that loop.
     */
    @Test
    public void aSimulationDistanceAboveThirtyTwoIsRefused() {
        final Instance instance = env.createFlatInstance();
        instance.setSimulationDistance(4);
        assertThrows(IllegalArgumentException.class, () -> instance.setSimulationDistance(33));
        assertThrows(IllegalArgumentException.class, () -> instance.setSimulationDistance(Integer.MAX_VALUE));
        assertEquals(4, instance.simulationDistance());
        instance.setSimulationDistance(32);
        assertEquals(32, instance.simulationDistance());
    }

    /**
     * ADR-M3-017 for a projectile: outside the simulation distance only its scheduler runs, not its
     * check for being stuck either. An arrow inside the stone of the floor is found stuck at once
     * where it is simulated.
     */
    @Test
    public void aProjectileOutsideTheSimulationDistanceIsNotFoundStuck() {
        final Instance instance = env.createFlatInstance();
        instance.setSimulationDistance(4);
        env.createPlayer(instance, PLAYER);
        final EntityProjectile arrow = new EntityProjectile(null, EntityType.ARROW);
        arrow.setInstance(instance, FAR.withY(20)).join();

        ticks(10);
        assertFalse(arrow.isOnGround(), "outside the simulation distance the arrow is not found stuck");
        assertFalse(arrow.hasNoGravity());

        instance.setSimulationDistance(-1);
        env.tick();
        assertTrue(arrow.isOnGround(), "simulated, it is");
    }
}
