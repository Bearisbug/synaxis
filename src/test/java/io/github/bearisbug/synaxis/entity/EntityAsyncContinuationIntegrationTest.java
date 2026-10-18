package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.entity.EntitySpawnEvent;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@EnvTest
public class EntityAsyncContinuationIntegrationTest {

    private static final class ProbeEntity extends Entity {
        final AtomicBoolean chunkRefreshed = new AtomicBoolean();
        final AtomicBoolean ownedOnChunkRefresh = new AtomicBoolean();

        ProbeEntity() {
            super(EntityType.ZOMBIE);
        }

        @Override
        protected void refreshCurrentChunk(Chunk currentChunk) {
            this.chunkRefreshed.set(true);
            this.ownedOnChunkRefresh.set(acquirable().isOwned());
            super.refreshCurrentChunk(currentChunk);
        }
    }

    /** Counts its ticks, and those that came while its spawn was under way. */
    private static final class SpawnProbe extends Entity {
        final AtomicInteger ticks = new AtomicInteger();
        final AtomicInteger ticksWhileSpawning = new AtomicInteger();
        volatile boolean spawning;

        SpawnProbe() {
            super(EntityType.ZOMBIE);
        }

        @Override
        public void tick(long time) {
            if (spawning) ticksWhileSpawning.incrementAndGet();
            ticks.incrementAndGet();
            super.tick(time);
        }
    }

    /**
     * Once armed, its next tick teleports it into a chunk that is not loaded and waits for the
     * teleport the three ways a future offers, recording what each did.
     */
    private static final class JoinProbe extends Entity {
        final List<String> outcomes = new CopyOnWriteArrayList<>();
        volatile @Nullable Pos target;

        JoinProbe() {
            super(EntityType.ZOMBIE);
        }

        @Override
        public void tick(long time) {
            super.tick(time);
            final Pos target = this.target;
            if (target == null) return;
            this.target = null;
            final CompletableFuture<Void> teleported = teleport(target);
            // A wait that blocks would never end: ended from another thread after 3 s, the test then fails
            final CountDownLatch waited = new CountDownLatch(1);
            Thread.ofPlatform().daemon().start(() -> {
                try {
                    if (!waited.await(3, TimeUnit.SECONDS)) teleported.complete(null);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            });
            outcomes.add(outcome("join", teleported::join));
            outcomes.add(outcome("get", teleported::get));
            outcomes.add(outcome("timed get", () -> teleported.get(1, TimeUnit.SECONDS)));
            waited.countDown();
        }

        private static String outcome(String name, Callable<?> wait) {
            final long since = System.nanoTime();
            try {
                wait.call();
                return name + " returned after " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since) + " ms";
            } catch (IllegalStateException e) {
                return name + " refused: " + e.getMessage();
            } catch (Exception e) {
                return name + " threw " + e;
            }
        }
    }

    @Test
    public void teleportContinuationOwnsTheEntity(Env env) {
        var instance = env.createFlatInstance();
        var entity = new ProbeEntity();
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        env.tick(); // Let the dispatcher assign a tick thread
        assertNotNull(entity.acquirable().assignedThread());

        entity.chunkRefreshed.set(false);
        // The target chunk is not loaded, so the continuation runs on the chunk loading thread
        // Joined outside the acquirable: the continuation needs it
        entity.acquirable().applySync(e -> e.teleport(new Pos(4000, 42, 4000))).join();
        assertTrue(entity.chunkRefreshed.get(), "the teleport did not cross a chunk");
        assertTrue(entity.ownedOnChunkRefresh.get(),
                "teleport continuation mutated the entity without owning it");
    }

    @Test
    public void instanceChangeContinuationOwnsTheEntity(Env env) {
        var origin = env.createFlatInstance();
        var target = env.createFlatInstance();
        var entity = new ProbeEntity();
        entity.setInstance(origin, new Pos(0, 42, 0)).join();
        env.tick(); // Let the dispatcher assign a tick thread
        assertNotNull(entity.acquirable().assignedThread());

        entity.chunkRefreshed.set(false);
        entity.acquirable().applySync(e -> e.setInstance(target, new Pos(4000, 42, 4000))).join();
        assertTrue(entity.chunkRefreshed.get(), "the entity was not moved to the new instance");
        assertTrue(entity.ownedOnChunkRefresh.get(),
                "setInstance continuation mutated the entity without owning it");
    }

    /**
     * An entity no tick thread owns yet spawns into a chunk that is not loaded: the spawn runs on the
     * thread that loaded the chunk, without a lock, and no tick thread ticks the entity until the spawn
     * is done, even with a whole tick passing while its {@link EntitySpawnEvent} listener runs.
     */
    @Test
    @Timeout(30)
    public void noTickThreadTicksAnEntityWhileItSpawns(Env env) throws InterruptedException {
        var instance = env.createFlatInstance();
        var entity = new SpawnProbe();
        var spawning = new CountDownLatch(1);
        var ticked = new CountDownLatch(1);
        env.process().eventHandler().addListener(EntitySpawnEvent.class, event -> {
            if (event.getEntity() != entity) return;
            entity.spawning = true;
            spawning.countDown();
            try {
                if (!ticked.await(10, TimeUnit.SECONDS)) fail("the test did not tick within 10 s");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            } finally {
                entity.spawning = false;
            }
        });
        var spawned = entity.setInstance(instance, new Pos(4000, 42, 4000));
        assertTrue(spawning.await(10, TimeUnit.SECONDS), "the spawn chunk loaded");
        env.tick();
        ticked.countDown();
        spawned.join();

        assertEquals(0, entity.ticksWhileSpawning.get(), "ticked by " + entity.acquirable().assignedThread()
                + " in the middle of its spawn");
        env.tick();
        assertNotNull(entity.acquirable().assignedThread(), "handed to a tick thread once spawned");
        assertTrue(entity.ticks.get() > 0, "ticked once spawned");
    }

    /**
     * A listener of the spawn moves the entity to another chunk: the dispatcher is told the chunk the
     * entity is in once the spawn is done, not the one it spawned in. The chunk it spawned in then
     * unloads, and the entity is still ticked, in the chunk it was moved to.
     */
    @Test
    public void anEntityMovedDuringItsSpawnTicksInTheChunkItWasMovedTo(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(5, 0).join();
        var entity = new SpawnProbe();
        var moved = new Pos(5 * 16 + 8, 42, 8);
        env.process().eventHandler().addListener(EntitySpawnEvent.class, event -> {
            if (event.getEntity() == entity) {
                var _ = entity.teleport(moved);
            }
        });
        entity.setInstance(instance, new Pos(4000, 42, 4000)).join(); // Chunk 250, 250: not loaded
        env.tick();
        assertSame(instance.getChunk(5, 0), entity.getChunk());

        instance.unloadChunk(250, 250);
        env.tick();
        final int before = entity.ticks.get();
        env.tick();
        assertEquals(before + 1, entity.ticks.get(), "ticked in the chunk it was moved to");
    }

    /**
     * On the tick thread owning it, an entity waits for its teleport into a chunk that is not loaded:
     * the teleport waits for that thread, so join and both get refuse at once instead of stopping the
     * process; the teleport itself lands once the tick is over.
     */
    @Test
    @Timeout(30)
    public void waitingForATeleportOnTheOwningThreadIsRefused(Env env) {
        var instance = env.createFlatInstance();
        var entity = new JoinProbe();
        entity.setInstance(instance, new Pos(8, 42, 8)).join();
        env.tick(); // Let the dispatcher assign a tick thread
        assertNotNull(entity.acquirable().assignedThread());

        var target = new Pos(1600.5, 42, 8.5); // Chunk 100, 0: not loaded
        assertFalse(instance.isChunkLoaded(100, 0));
        entity.target = target;
        env.tick();

        assertEquals(3, entity.outcomes.size(), entity.outcomes.toString());
        for (String outcome : entity.outcomes)
            assertTrue(outcome.contains(" refused: Joining the teleport or instance change of entity "), outcome);
        // In the chunk of the target, not on its point: a tick after the landing lets it fall
        assertTrue(env.tickWhile(() -> !entity.getPosition().sameChunk(target), Duration.ofSeconds(10)),
                "the teleport landed once the tick was over: " + entity.getPosition());
    }
}
