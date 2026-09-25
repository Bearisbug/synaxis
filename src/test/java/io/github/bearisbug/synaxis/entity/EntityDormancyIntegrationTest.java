package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityPositionSyncPacket;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import io.github.bearisbug.synaxis.thread.TickThread;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Entities sitting out ticks while players are far away ({@code docs/DESIGN-PROD.md}
 * {@code REQ-PROD-040}, {@code API-PROD-019}).
 */
@EnvTest
public class EntityDormancyIntegrationTest {

    private static final Pos SPAWN = new Pos(0.5, 42, 0.5);
    /** The §19 policy: every 20 ticks with the nearest player farther than 32 blocks. */
    private static final DormancyPolicy FAR_AFTER_32 = (_, distance) -> distance > 32 ? 20 : 1;

    /** Counts the ticks it runs, from whichever tick thread. */
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

    private static Counting spawn(Instance instance, Pos at) {
        final Counting entity = new Counting();
        entity.setInstance(instance, at).join();
        return entity;
    }

    private static void ticks(Env env, int count) {
        for (int i = 0; i < count; i++) env.tick();
    }

    private static long syncsOf(List<EntityPositionSyncPacket> packets, Entity entity) {
        return packets.stream().filter(packet -> packet.entityId() == entity.getEntityId()).count();
    }

    /** The §19 scenario: a player 40 blocks away for 100 ticks. */
    @Test
    public void aFarEntityRunsOnceEvery20TicksAndSendsNoPositionSync(Env env) {
        final Instance instance = env.createFlatInstance();
        final TestConnection connection = env.createConnection();
        connection.connect(instance, SPAWN);
        final Counting dormant = spawn(instance, new Pos(40.5, 42, 0.5));
        final Counting awake = spawn(instance, new Pos(40.5, 42, 8.5));
        dormant.setDormancyPolicy(FAR_AFTER_32);
        // The first tick asks the instance for the players, which it has from the next tick on
        ticks(env, 2);
        dormant.updates.set(0);
        awake.updates.set(0);
        final long aliveTicks = dormant.getAliveTicks();

        final Collector<EntityPositionSyncPacket> syncs = connection.trackIncoming(EntityPositionSyncPacket.class);
        ticks(env, 100);

        assertEquals(5, dormant.updates.get(), "a dormant entity runs once every 20 ticks");
        assertEquals(aliveTicks + 5, dormant.getAliveTicks(), "only the ticks it ran are counted");
        assertEquals(100, awake.updates.get(), "an entity without a policy runs every tick");
        final List<EntityPositionSyncPacket> sent = syncs.collect();
        assertEquals(0, syncsOf(sent, dormant), "no position synchronization while dormant");
        assertEquals(5, syncsOf(sent, awake), "the player sees both, the one running every tick is synchronized");
    }

    /** Synchronizing every tick it runs, and asked for one more: still nothing until it wakes up. */
    @Test
    public void noPositionSynchronizationIsSentWhileDormantEvenWhenDue(Env env) {
        final Instance instance = env.createFlatInstance();
        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(instance, SPAWN);
        final Counting entity = spawn(instance, new Pos(40.5, 42, 0.5));
        entity.setSynchronizationTicks(1);
        entity.setDormancyPolicy(FAR_AFTER_32);
        ticks(env, 2);

        Collector<EntityPositionSyncPacket> syncs = connection.trackIncoming(EntityPositionSyncPacket.class);
        entity.synchronizeNextTick();
        ticks(env, 100);
        assertEquals(0, syncsOf(syncs.collect(), entity));

        player.acquirable().sync(owned -> owned.teleport(new Pos(37.5, 42, 0.5)).join());
        ticks(env, 5);
        syncs = connection.trackIncoming(EntityPositionSyncPacket.class);
        ticks(env, 10);
        assertEquals(10, syncsOf(syncs.collect(), entity), "awake, it synchronizes on every tick again");
    }

    @Test
    public void aPlayerComingNearWakesTheEntityWithinFiveTicks(Env env) {
        final Instance instance = env.createFlatInstance();
        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(instance, SPAWN);
        final Counting entity = spawn(instance, new Pos(40.5, 42, 0.5));
        entity.setDormancyPolicy(FAR_AFTER_32);
        ticks(env, 25);

        final Collector<EntityPositionSyncPacket> syncs = connection.trackIncoming(EntityPositionSyncPacket.class);
        player.acquirable().sync(owned -> owned.teleport(new Pos(37.5, 42, 0.5)).join());
        ticks(env, 5);
        assertEquals(1, syncsOf(syncs.collect(), entity), "waking up synchronizes the position once");
        entity.updates.set(0);
        ticks(env, 10);
        assertEquals(10, entity.updates.get(), "a player 3 blocks away has it run every tick");
    }

    @Test
    public void theSchedulerOfADormantEntityRunsEveryTick(Env env) {
        final Instance instance = env.createFlatInstance();
        env.createPlayer(instance, SPAWN);
        final Counting entity = spawn(instance, new Pos(40.5, 42, 0.5));
        entity.setDormancyPolicy(FAR_AFTER_32);
        ticks(env, 2);
        final AtomicInteger tasks = new AtomicInteger();
        entity.scheduler().buildTask(tasks::incrementAndGet).repeat(TaskSchedule.tick(1)).schedule();
        entity.updates.set(0);

        ticks(env, 20);

        assertEquals(1, entity.updates.get());
        assertEquals(20, tasks.get(), "a task handed to a dormant entity is not held back");
    }

    @Test
    public void clearingThePolicyRunsEveryTickAgain(Env env) {
        final Instance instance = env.createFlatInstance();
        env.createPlayer(instance, SPAWN);
        final Counting entity = spawn(instance, new Pos(40.5, 42, 0.5));
        entity.setDormancyPolicy(FAR_AFTER_32);
        assertSame(FAR_AFTER_32, entity.getDormancyPolicy());
        ticks(env, 2);

        entity.setDormancyPolicy(null);
        assertNull(entity.getDormancyPolicy());
        entity.updates.set(0);
        ticks(env, 20);
        assertEquals(20, entity.updates.get());
    }

    @Test
    public void aPlayerCannotBeDormant(Env env) {
        final Player player = env.createPlayer(env.createFlatInstance(), SPAWN);
        assertThrows(IllegalArgumentException.class, () -> player.setDormancyPolicy(FAR_AFTER_32));
        player.setDormancyPolicy(null);
        assertNull(player.getDormancyPolicy());
    }

    @Test
    public void withoutPlayersTheNearestIsInfinitelyFar(Env env) {
        final Instance instance = env.createFlatInstance();
        final Counting entity = spawn(instance, SPAWN);
        final List<Double> distances = new CopyOnWriteArrayList<>();
        entity.setDormancyPolicy((_, distance) -> {
            distances.add(distance);
            return 10;
        });
        ticks(env, 2);
        entity.updates.set(0);
        ticks(env, 20);

        assertEquals(Double.POSITIVE_INFINITY, distances.getLast());
        assertEquals(2, entity.updates.get());
    }

    /** The hook of the plugin's AI: every 5 ticks, on the tick thread that owns the entity. */
    @Test
    public void thePolicyIsAskedEveryFiveTicksOnTheTickThreadOfTheEntity(Env env) {
        final Instance instance = env.createFlatInstance();
        env.createPlayer(instance, SPAWN);
        // Another dormant entity has the instance take the player positions already
        spawn(instance, new Pos(-40.5, 42, 0.5)).setDormancyPolicy(FAR_AFTER_32);
        ticks(env, 2);
        final Counting entity = spawn(instance, new Pos(40.5, 42, 0.5));
        final AtomicInteger tick = new AtomicInteger();
        final List<Integer> asked = new CopyOnWriteArrayList<>();
        final List<String> foreign = new CopyOnWriteArrayList<>();
        entity.setDormancyPolicy((self, _) -> {
            asked.add(tick.get());
            if (!(Thread.currentThread() instanceof TickThread) || !self.acquirable().isOwned())
                foreign.add(Thread.currentThread().getName());
            return 20;
        });
        for (int i = 0; i < 40; i++) {
            tick.set(i);
            env.tick();
        }

        assertEquals(List.of(), foreign, "asked on a thread that does not own the entity");
        assertEquals(0, asked.getFirst(), "asked on the first tick after it was set");
        // From then on at the ticks its id puts it on, 1 to 5 ticks after the first
        assertTrue(asked.get(1) >= 1 && asked.get(1) <= 5, "asked " + asked);
        for (int i = 2; i < asked.size(); i++)
            assertEquals(5, asked.get(i) - asked.get(i - 1), "asked every 5 ticks: " + asked);
        assertTrue(asked.size() >= 8, "asked " + asked);
    }

    /**
     * The player moves during the tick, before the entity asks its policy: the entity still gets where
     * the player stood when the tick started, the same for every tick thread.
     */
    @Test
    public void theDistanceIsWhereThePlayersStoodWhenTheTickStarted(Env env) {
        final Instance instance = env.createFlatInstance();
        final Player player = env.createPlayer(instance, new Pos(2.5, 42, 2.5));
        // In the chunk of the player, so that its scheduler may move the player
        final Counting entity = spawn(instance, new Pos(12.5, 42, 12.5));
        entity.setDormancyPolicy(FAR_AFTER_32);
        ticks(env, 2);

        final List<Double> distances = new CopyOnWriteArrayList<>();
        entity.scheduler().scheduleNextTick(() -> player.teleport(new Pos(15.5, 42, 15.5)).join());
        entity.setDormancyPolicy((_, distance) -> {
            distances.add(distance);
            return 1;
        });
        env.tick();
        assertEquals(List.of(Math.hypot(10, 10)), distances, "the player had moved to 3 blocks by then");

        entity.setDormancyPolicy((_, distance) -> {
            distances.add(distance);
            return 1;
        });
        env.tick();
        assertEquals(Math.hypot(3, 3), distances.getLast(), 1e-9, "the next tick starts with the player moved");
    }
}
