package io.github.bearisbug.synaxis.instance.light;

import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.instance.LightingChunk;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class LightDeadlockIntegrationTest {

    // A handler reading light from onPlace runs while the instance holds the chunk write-lock,
    // relighting from there must not hand the section to a pool thread that waits for that lock.
    @Test
    public void relightInsideBlockHandlerDoesNotDeadlock(Env env) throws InterruptedException {
        var instance = env.createFlatInstance();
        instance.setChunkSupplier(LightingChunk::new);
        instance.loadChunk(0, 0).join();

        var probed = new AtomicInteger(-1);
        BlockHandler handler = new BlockHandler() {
            @Override
            public Key getKey() {
                return Key.key("test:light_probe");
            }

            @Override
            public void onPlace(Placement placement) {
                probed.set(placement.getInstance().getBlockLight(1, 41, 1));
            }
        };

        var thread = Thread.ofVirtual().unstarted(() ->
                instance.setBlock(1, 41, 1, Block.GLOWSTONE.withHandler(handler)));
        thread.start();
        assertTrue(thread.join(Duration.ofSeconds(10)), "setBlock deadlocked inside the lighting engine");
        assertEquals(15, probed.get(), "the glowstone light level was not visible to the handler");
    }

    // REQ-M3-004: relight runs on a snapshot of the blocks and never takes the instance monitor, so it finishes while
    // another thread holds that monitor.
    @Test
    public void relightDoesNotWaitForTheInstanceMonitor(Env env) throws InterruptedException {
        var instance = env.createFlatInstance();
        instance.setChunkSupplier(LightingChunk::new);
        instance.loadChunk(0, 0).join();
        instance.setBlock(1, 41, 1, Block.GLOWSTONE);

        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = Thread.ofVirtual().start(() -> {
            synchronized (instance) {
                held.countDown();
                awaitUninterruptibly(release);
            }
        });
        held.await();
        try {
            var relight = Thread.ofVirtual().start(() -> LightingChunk.relight(instance, instance.getChunks()));
            assertTrue(relight.join(Duration.ofSeconds(10)), "relight waited for the instance monitor");
        } finally {
            release.countDown();
            holder.join();
        }
        assertEquals(14, instance.getChunk(0, 0).getSectionAt(41).blockLight().getLevel(2, 41 & 15, 1));
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        while (true) {
            try {
                latch.await();
                return;
            } catch (InterruptedException _) {
                // keep holding the monitor until released
            }
        }
    }
}
