package io.github.bearisbug.synaxis.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sibling {@link MetadataDef} subclasses have no class initialization order between them, so their
 * static initializers can populate the shared index tables from several threads at once.
 */
public class MetadataDefConcurrencyTest {

    private static final int THREADS = 8;
    private static final int ENTRIES = 512;

    @Test
    @Timeout(60)
    public void concurrentStoresKeepEveryIndex() throws InterruptedException {
        final String prefix = "synaxis.test." + UUID.randomUUID() + ".";
        final String shared = prefix + "Shared";
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(THREADS);
        for (int thread = 0; thread < THREADS; thread++) {
            final int id = thread;
            Thread.startVirtualThread(() -> {
                try {
                    start.await();
                    for (int i = 1; i <= ENTRIES; i++) {
                        MetadataDefImpl.storeMaxIndex(prefix + id + "." + i, i);
                        MetadataDefImpl.storeMaxIndex(shared, i);
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "the storing threads did not finish");

        assertEquals(ENTRIES, MetadataDefImpl.MAX_INDEX.get(shared),
                "a concurrent store lost the highest index of a shared key");
        for (int thread = 0; thread < THREADS; thread++) {
            for (int i = 1; i <= ENTRIES; i++) {
                assertEquals(i, MetadataDefImpl.MAX_INDEX.get(prefix + thread + "." + i),
                        "a concurrent store lost the index of another thread");
            }
        }
    }
}
