package io.github.bearisbug.synaxis.instance;

import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class TickableBlockIntegrationTest {

    // Making a block non-tickable from its own tick is the usual way to write a furnace or a crop,
    // the removal must not make the iteration skip the entries behind it.
    @Test
    public void handlerRemovingItselfDoesNotSkipOtherBlocks(Env env) {
        var instance = env.createFlatInstance();
        var chunk = instance.loadChunk(0, 0).join();

        Set<Point> ticked = new HashSet<>();
        BlockHandler handler = new BlockHandler() {
            @Override
            public Key getKey() {
                return Key.key("test:self_removing");
            }

            @Override
            public boolean isTickable() {
                return true;
            }

            @Override
            public void tick(Tick tick) {
                ticked.add(tick.getBlockPosition());
                tick.getInstance().setBlock(tick.getBlockPosition(), Block.STONE);
            }
        };

        for (int x = 0; x < 16; x++) instance.setBlock(x, 41, 0, Block.DIRT.withHandler(handler));
        chunk.tick(0);
        assertEquals(16, ticked.size(), "tick iteration skipped entries after a self-removal");
    }

    // Chunk#reset leaves nothing of the blocks before it: no block tick, no cached chunk packet
    @Test
    public void resetStopsTheBlockTicksAndDropsTheCachedPacket(Env env) {
        var instance = env.createFlatInstance();
        var chunk = instance.loadChunk(0, 0).join();
        final AtomicInteger ticks = new AtomicInteger();
        instance.setBlock(0, 41, 0, Block.DIRT.withHandler(tickable("test:reset", _ -> ticks.incrementAndGet())));
        final CachedPacket packet = (CachedPacket) chunk.getFullDataPacket();
        assertNotNull(packet.body(ConnectionState.PLAY, env.process().registries(), 0));
        assertTrue(packet.isValid());

        chunk.lockWriteLock();
        try {
            chunk.reset();
        } finally {
            chunk.unlockWriteLock();
        }
        chunk.tick(0);
        chunk.tick(0);

        assertEquals(0, ticks.get(), "block ticks after the reset");
        assertFalse(packet.isValid(), "the chunk packet of the blocks before the reset is still cached");
        assertEquals(Block.AIR, instance.getBlock(0, 41, 0));
        assertEquals(Block.AIR, instance.getBlock(0, 39, 0));
        assertEquals(instance.getCachedDimensionType().minY() - 1, chunk.motionBlockingHeightmap().getHeight(0, 0),
                "the heightmap of an empty column");
    }

    // A handler throwing on every tick does not keep the other handlers of its chunk from ticking
    @Test
    public void aThrowingHandlerDoesNotStopTheOthersOfItsChunk(Env env) {
        var instance = env.createFlatInstance();
        var chunk = instance.loadChunk(0, 0).join();
        final List<Throwable> reported = new CopyOnWriteArrayList<>();
        env.process().exception().setExceptionHandler(reported::add);
        final AtomicInteger counted = new AtomicInteger();
        final BlockHandler throwing = tickable("test:throwing", _ -> {
            throw new IllegalStateException("a failing tick");
        });
        final BlockHandler counting = tickable("test:counting", _ -> counted.incrementAndGet());
        // 16 of each: in whatever order the chunk holds them, throwing ones come before counting ones
        for (int x = 0; x < 16; x++) {
            instance.setBlock(x, 41, 0, Block.DIRT.withHandler(throwing));
            instance.setBlock(x, 41, 1, Block.DIRT.withHandler(counting));
        }

        for (int tick = 0; tick < 3; tick++) chunk.tick(0);

        assertEquals(3 * 16, counted.get(), "ticks of the counting blocks");
        assertEquals(3 * 16, reported.size(), "exceptions reported");
    }

    // A block destroyed by a handler ticked before it in the same chunk tick does not tick: ticked with
    // the block it had, it would set that block again, back into the world
    @Test
    public void aBlockDestroyedEarlierInTheTickDoesNotTick(Env env) {
        var instance = env.createFlatInstance();
        var chunk = instance.loadChunk(0, 0).join();
        final Point first = new Vec(0, 41, 0), second = new Vec(1, 41, 0);
        final List<Point> ticked = new CopyOnWriteArrayList<>();
        // Destroys the other block, then moves its own block on the usual way, by setting it again
        final BlockHandler rival = tickable("test:rival", tick -> {
            final Point self = tick.getBlockPosition();
            ticked.add(self);
            tick.getInstance().setBlock(self.samePoint(first) ? second : first, Block.AIR);
            tick.getInstance().setBlock(self, tick.getBlock());
        });
        instance.setBlock(first, Block.DIRT.withHandler(rival));
        instance.setBlock(second, Block.DIRT.withHandler(rival));

        chunk.tick(0);

        assertEquals(1, ticked.size(), "ticked: " + ticked);
        final Point survivor = ticked.getFirst();
        assertFalse(instance.getBlock(survivor).air(), "the block ticked first is still there");
        assertTrue(instance.getBlock(survivor.samePoint(first) ? second : first).air(), "the block it destroyed came back");
    }

    private static BlockHandler tickable(String key, Consumer<BlockHandler.Tick> onTick) {
        return new BlockHandler() {
            @Override
            public Key getKey() {
                return Key.key(key);
            }

            @Override
            public boolean isTickable() {
                return true;
            }

            @Override
            public void tick(Tick tick) {
                onTick.accept(tick);
            }
        };
    }
}
