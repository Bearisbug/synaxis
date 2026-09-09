package net.minestom.server.utils.block;

import net.minestom.server.instance.Instance;
import net.minestom.server.instance.batch.AbsoluteBlockBatch;
import net.minestom.server.instance.batch.Batch;
import net.minestom.server.instance.batch.BatchOption;
import net.minestom.server.instance.batch.ChunkBatch;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@EnvTest
public class BlockBatchIntegrationTest {

    @Test
    public void inverseConsumerNotNull(Env env) {
        Instance instance = env.createFlatInstance();

        AbsoluteBlockBatch blockBatch = new AbsoluteBlockBatch(new BatchOption().setCalculateInverse(true));
        blockBatch.setBlock(0, 0, 0, Block.SNOW);

        CountDownLatch latch = new CountDownLatch(1);
        instance.loadChunk(0, 0).join();
        blockBatch.apply(instance, (inverse) -> {
            Assertions.assertNotNull(inverse);
            latch.countDown();
        });
        env.tickWhile(() -> latch.getCount() > 0, Duration.ofSeconds(1));
        Assertions.assertDoesNotThrow(()->Assertions.assertTrue(latch.await(1, TimeUnit.SECONDS)));
    }

    @Test
    public void fullChunkBatchRewritesTheChunk(Env env) throws InterruptedException {
        Instance instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();

        ChunkBatch chunkBatch = new ChunkBatch(new BatchOption().setFullChunk(true));
        chunkBatch.setBlock(0, 41, 0, Block.SNOW);

        CountDownLatch latch = new CountDownLatch(1);
        chunkBatch.apply(instance, 0, 0, _ -> latch.countDown());
        env.tickWhile(() -> latch.getCount() > 0, Duration.ofSeconds(1));
        Assertions.assertTrue(latch.await(1, TimeUnit.SECONDS), "the full chunk batch never completed");
        Assertions.assertEquals(Block.SNOW, instance.getBlock(0, 41, 0));
        Assertions.assertEquals(Block.AIR, instance.getBlock(0, 39, 0), "the chunk was not cleared first");
    }

    @Test
    public void batchOnUnloadedChunkReleasesItsInverse(Env env) throws InterruptedException {
        Instance instance = env.createFlatInstance();
        var chunk = instance.loadChunk(0, 0).join();

        ChunkBatch chunkBatch = new ChunkBatch(new BatchOption().setCalculateInverse(true));
        chunkBatch.setBlock(0, 41, 0, Block.SNOW);
        instance.unloadChunk(chunk);

        ChunkBatch inverse = chunkBatch.apply(instance, chunk, null);
        Assertions.assertNotNull(inverse);
        awaitReady(inverse);
    }

    private static void awaitReady(Batch<?> batch) throws InterruptedException {
        for (int i = 0; i < 200 && !batch.isReady(); i++) Thread.sleep(5);
        Assertions.assertTrue(batch.isReady(), "the inverse batch was never released, applying it would block forever");
    }

    @Test
    public void inverseConsumerNull(Env env) {
        Instance instance = env.createFlatInstance();

        AbsoluteBlockBatch blockBatch = new AbsoluteBlockBatch(new BatchOption().setCalculateInverse(false));
        blockBatch.setBlock(0, 0, 0, Block.SNOW);

        CountDownLatch latch = new CountDownLatch(1);
        instance.loadChunk(0, 0).join();
        blockBatch.apply(instance, (inverse) -> {
            Assertions.assertNull(inverse);
            latch.countDown();
        });
        env.tickWhile(() -> latch.getCount() > 0, Duration.ofSeconds(1));
        Assertions.assertDoesNotThrow(()->Assertions.assertTrue(latch.await(1, TimeUnit.SECONDS)));
    }

    @Test
    public void inverseConsumerNotNullUnsafe(Env env) {
        Instance instance = env.createFlatInstance();

        AbsoluteBlockBatch blockBatch = new AbsoluteBlockBatch(new BatchOption().setCalculateInverse(true));
        blockBatch.setBlock(0, 0, 0, Block.SNOW);

        CountDownLatch latch = new CountDownLatch(1);
        instance.loadChunk(0, 0).join();
        blockBatch.unsafeApply(instance, (inverse) -> {
            Assertions.assertNotNull(inverse);
            latch.countDown();
        });
        env.tickWhile(() -> latch.getCount() > 0, Duration.ofSeconds(1));
        Assertions.assertDoesNotThrow(()->Assertions.assertTrue(latch.await(1, TimeUnit.SECONDS)));
    }

    @Test
    public void inverseConsumerNullUnsafe(Env env) {
        Instance instance = env.createFlatInstance();

        AbsoluteBlockBatch blockBatch = new AbsoluteBlockBatch(new BatchOption().setCalculateInverse(false));
        blockBatch.setBlock(0, 0, 0, Block.SNOW);

        CountDownLatch latch = new CountDownLatch(1);
        instance.loadChunk(0, 0).join();
        blockBatch.unsafeApply(instance, (inverse) -> {
            Assertions.assertNull(inverse);
            latch.countDown();
        });
        env.tickWhile(() -> latch.getCount() > 0, Duration.ofSeconds(1));
        Assertions.assertDoesNotThrow(()->Assertions.assertTrue(latch.await(1, TimeUnit.SECONDS)));
    }
}
