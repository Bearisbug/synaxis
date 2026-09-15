package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.entity.metadata.avatar.MannequinMeta;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MetadataHolderConcurrencyTest {

    @Test
    public void concurrentWritesAreNotLost() throws InterruptedException {
        // The entries array is grown with a read-copy-write, two writers racing on it lose one value
        for (int attempt = 0; attempt < 200; attempt++) {
            var holder = new MetadataHolder(_ -> {
            });
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(2);
            Thread.startVirtualThread(() -> {
                await(start);
                holder.set(MetadataDef.ArmorStand.IS_SMALL, true);
                done.countDown();
            });
            Thread.startVirtualThread(() -> {
                await(start);
                holder.set(MetadataDef.AIR_TICKS, 42);
                done.countDown();
            });
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));

            assertTrue(holder.get(MetadataDef.ArmorStand.IS_SMALL), "attempt " + attempt);
            assertEquals(42, holder.get(MetadataDef.AIR_TICKS), "attempt " + attempt);
        }
    }

    @Test
    public void disablingOneMannequinPartKeepsTheOthers() {
        var mannequin = new Entity(EntityType.MANNEQUIN);
        var meta = (MannequinMeta) mannequin.getEntityMeta();
        assertTrue(meta.isCapeEnabled());

        meta.setHatEnabled(false);

        assertFalse(meta.isHatEnabled());
        assertTrue(meta.isCapeEnabled(), "disabling the hat cleared every other model part");
        assertEquals((byte) 0x3F, meta.getDisplayedSkinParts());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
