package io.github.bearisbug.synaxis.instance;

import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.world.DimensionType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dispatcher partition of a chunk being cached ({@code docs/DESIGN-PROD.md} §16): an entity moving
 * into the chunk as soon as other threads see it keeps ticking, and the chunk, whose partition is
 * signalled before the instance holds it, does not tick until it does.
 */
@EnvTest
public class ChunkPartitionIntegrationTest {

    // The thread caching chunk (1, 0) is held right after other threads see the chunk, on the lock of the
    // chunk tickets, while the entity moves into it on its own thread; then both go on
    @Test
    public void anEntityMovingIntoAChunkBeingCachedKeepsTicking(Env env) throws ReflectiveOperationException {
        final Instance instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        final Entity entity = new Entity(EntityType.ZOMBIE);
        entity.setNoGravity(true);
        entity.setInstance(instance, new Pos(15.5, 40, 0.5)).join();
        env.tick();

        final CompletableFuture<Chunk> loading;
        synchronized (ticketsLock(instance)) {
            loading = instance.loadChunk(1, 0);
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (instance.getChunk(1, 0) == null && System.nanoTime() < deadline) LockSupport.parkNanos(100_000);
            final Chunk target = instance.getChunk(1, 0);
            assertTrue(target != null && !loading.isDone(), "chunk (1, 0) seen while it is being cached");
            // The chunk is loaded: the teleport moves the entity before it returns
            entity.acquirable().sync(owned -> {
                var _ = owned.teleport(new Pos(16.5, 40, 0.5));
            });
            assertSame(target, entity.getChunk());
        }
        loading.join();

        final long aliveTicks = entity.getAliveTicks();
        env.tick();
        env.tick();
        assertEquals(aliveTicks + 2, entity.getAliveTicks(), "ticks of the entity after it moved into the chunk");
    }

    // The thread caching chunk (0, 0) is held between the partition signal and the caching: the ticks
    // meanwhile create the partition and do not tick the chunk, its handler would not find it
    @Test
    public void aChunkDoesNotTickBeforeTheInstanceHoldsIt(Env env) throws InterruptedException {
        final CountDownLatch signalled = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final InstanceContainer instance = new InstanceContainer(env.process(), UUID.randomUUID(), DimensionType.OVERWORLD) {
            @Override
            void beforeCaching(Chunk chunk) {
                signalled.countDown();
                try {
                    boolean _ = release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        env.process().instance().registerInstance(instance);
        final List<Boolean> foundItsChunk = new CopyOnWriteArrayList<>();
        final BlockHandler handler = new BlockHandler() {
            @Override
            public Key getKey() {
                return Key.key("test:partition");
            }

            @Override
            public boolean isTickable() {
                return true;
            }

            @Override
            public void tick(Tick tick) {
                foundItsChunk.add(tick.getInstance().getChunkAt(tick.getBlockPosition()) != null);
            }
        };
        instance.setGenerator(unit -> unit.modifier().setBlock(unit.absoluteStart().add(0, 64, 0), Block.STONE.withHandler(handler)));

        final CompletableFuture<Chunk> loading = instance.loadChunk(0, 0);
        try {
            assertTrue(signalled.await(10, TimeUnit.SECONDS), "the chunk reached its caching");
            env.tick();
            env.tick();
            assertEquals(List.of(), foundItsChunk, "ticks of the handler before the instance held its chunk");
        } finally {
            release.countDown();
        }
        loading.join();
        env.tick();
        assertEquals(List.of(true), foundItsChunk, "ticks of the handler once the instance held its chunk");
    }

    private static Object ticketsLock(Instance instance) throws ReflectiveOperationException {
        final Field lock = ChunkTickets.class.getDeclaredField("lock");
        lock.setAccessible(true);
        return lock.get(instance.tickets);
    }
}
