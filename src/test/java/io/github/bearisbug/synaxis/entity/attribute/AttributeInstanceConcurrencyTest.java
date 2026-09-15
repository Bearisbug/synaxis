package io.github.bearisbug.synaxis.entity.attribute;

import org.junit.jupiter.api.Test;

import java.util.List;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AttributeInstanceConcurrencyTest {

    @Test
    public void concurrentModifiersAreAllReflected() throws InterruptedException {
        for (int attempt = 0; attempt < 500; attempt++) {
            var instance = new AttributeInstance(Attribute.MAX_HEALTH, 20, List.of(), null);
            var first = new AttributeModifier("synaxis:first", 1, AttributeOperation.ADD_VALUE);
            var second = new AttributeModifier("synaxis:second", 2, AttributeOperation.ADD_VALUE);

            var start = new CountDownLatch(1);
            var done = new CountDownLatch(2);
            Thread.startVirtualThread(() -> {
                await(start);
                instance.addModifier(first);
                done.countDown();
            });
            Thread.startVirtualThread(() -> {
                await(start);
                instance.addModifier(second);
                done.countDown();
            });
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));

            assertEquals(23, instance.getValue(), "cached value lost a modifier on attempt " + attempt);
        }
    }

    @Test
    public void baseValueChangeUsesTheCurrentBase() {
        var instance = new AttributeInstance(Attribute.MAX_HEALTH, 20, List.of(), null);
        instance.setBaseValue(30);
        assertEquals(30, instance.getValue());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
