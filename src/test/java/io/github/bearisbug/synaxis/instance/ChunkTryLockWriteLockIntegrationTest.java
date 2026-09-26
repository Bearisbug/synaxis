package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Chunk#tryLockWriteLock}, the bounded wait the overlay encoder takes the chunk write-lock
 * with ({@code docs/DESIGN-M3.md} API-M3-005, §16 判死时编码遇到被卡住的区块锁).
 */
@EnvTest
public class ChunkTryLockWriteLockIntegrationTest {

    @Test
    public void takesAFreeLock(Env env) {
        final Chunk chunk = env.createFlatInstance().loadChunk(0, 0).join();
        assertTrue(chunk.tryLockWriteLock(Duration.ofMillis(50)));
        try {
            assertTrue(chunk.holdsWriteLock());
        } finally {
            chunk.unlockWriteLock();
        }
        assertFalse(chunk.holdsWriteLock());
    }

    @Test
    public void isReentrantForTheHolder(Env env) {
        final Chunk chunk = env.createFlatInstance().loadChunk(0, 0).join();
        chunk.lockWriteLock();
        try {
            assertTrue(chunk.tryLockWriteLock(Duration.ZERO));
            chunk.unlockWriteLock();
            assertTrue(chunk.holdsWriteLock(), "the outer hold is still there");
        } finally {
            chunk.unlockWriteLock();
        }
    }

    @Test
    public void givesUpAfterTheTimeoutWhileAnotherThreadHoldsIt(Env env) throws Exception {
        final Chunk chunk = env.createFlatInstance().loadChunk(0, 0).join();
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder = Thread.ofPlatform().start(() -> {
            chunk.lockWriteLock();
            try {
                held.countDown();
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } finally {
                chunk.unlockWriteLock();
            }
        });
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS));
            final long start = System.nanoTime();
            assertFalse(chunk.tryLockWriteLock(Duration.ofMillis(50)));
            final long waited = System.nanoTime() - start;
            assertTrue(waited >= TimeUnit.MILLISECONDS.toNanos(50), "waited " + waited + " ns");
            assertFalse(chunk.holdsWriteLock());
        } finally {
            release.countDown();
            holder.join();
        }
        assertTrue(chunk.tryLockWriteLock(Duration.ofMillis(50)), "free again once the holder is done");
        chunk.unlockWriteLock();
    }

    @Test
    public void anInterruptedWaitGivesUpAndKeepsTheInterrupt(Env env) throws Exception {
        final Chunk chunk = env.createFlatInstance().loadChunk(0, 0).join();
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder = Thread.ofPlatform().start(() -> {
            chunk.lockWriteLock();
            try {
                held.countDown();
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } finally {
                chunk.unlockWriteLock();
            }
        });
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            assertFalse(chunk.tryLockWriteLock(Duration.ofSeconds(5)));
            assertTrue(Thread.interrupted(), "the interrupt is kept for the caller");
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    public void readyToUnloadDefaultsToTrue(Env env) {
        final Chunk chunk = env.createFlatInstance().loadChunk(0, 0).join();
        assertTrue(chunk.readyToUnload());
    }
}
