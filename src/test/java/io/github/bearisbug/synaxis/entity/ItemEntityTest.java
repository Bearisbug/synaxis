package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ItemEntityTest {

    @Test
    public void theBoundingBoxIsTheOneOfTheRegistry() {
        final ItemEntity item = new ItemEntity(ItemStack.of(Material.STONE));
        assertEquals(EntityType.ITEM.boundingBox(), item.getBoundingBox());
    }

    @Test
    public void thePickupDelayRunsFromConstructionUntilSpawned() {
        final ItemEntity item = new ItemEntity(ItemStack.of(Material.STONE));
        assertTrue(item.getTimeSinceSpawn() < 60_000, "time since spawn " + item.getTimeSinceSpawn() + " ms");
        assertTrue(item.isPickable());

        item.setPickupDelay(Duration.ofHours(1));
        assertFalse(item.isPickable());
    }
}
