package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.entity.EntityItemMergeEvent;
import io.github.bearisbug.synaxis.event.item.PickupItemEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Picking up and merging both check the item entity, fire a cancellable event and only then remove it.
 * The interleaving reproduced here (a second scan running from inside the event, before the removal)
 * is what two tick threads produce when they scan the same item entity in the same tick.
 */
@EnvTest
public class ItemEntityClaimIntegrationTest {

    private static final long TIME = 10_000;

    @Test
    public void itemIsPickedUpOnce(Env env) {
        var instance = env.createFlatInstance();
        var spawn = new Pos(0, 42, 0);
        var first = picker(instance, spawn);
        var second = picker(instance, spawn);
        var item = spawnItem(instance, spawn, 1);

        var pickups = new AtomicInteger();
        env.process().eventHandler().addListener(PickupItemEvent.class, _ -> {
            if (pickups.incrementAndGet() == 1) second.update(TIME);
        });

        first.update(TIME);
        assertEquals(1, pickups.get(), "the same item entity was handed to two pickers");
        assertTrue(item.isRemoved());
    }

    @Test
    public void cancelledPickupLeavesTheItemAvailable(Env env) {
        var instance = env.createFlatInstance();
        var spawn = new Pos(0, 42, 0);
        var picker = picker(instance, spawn);
        var item = spawnItem(instance, spawn, 1);

        env.process().eventHandler().addListener(PickupItemEvent.class, event -> event.setCancelled(true));
        picker.update(TIME);

        assertFalse(item.isRemoved());
        assertTrue(item.isPickable());
    }

    @Test
    public void itemsMergeOnce(Env env) {
        var instance = env.createFlatInstance();
        var spawn = new Pos(0, 42, 0);
        var first = spawnItem(instance, spawn, 1);
        var second = spawnItem(instance, spawn, 1);

        var merges = new AtomicInteger();
        env.process().eventHandler().addListener(EntityItemMergeEvent.class, _ -> {
            if (merges.incrementAndGet() == 1) second.update(TIME);
        });

        first.update(TIME);
        assertEquals(1, merges.get(), "the same pair of item entities merged twice");
        assertFalse(first.isRemoved() && second.isRemoved(), "both sides of the merge were removed");
        assertEquals(2, first.getItemStack().amount());
        assertTrue(second.isRemoved());
    }

    @Test
    public void cancelledMergeLeavesBothItemsClaimable(Env env) {
        var instance = env.createFlatInstance();
        var spawn = new Pos(0, 42, 0);
        var first = spawnItem(instance, spawn, 1);
        var second = spawnItem(instance, spawn, 1);

        env.process().eventHandler().addListener(EntityItemMergeEvent.class, event -> event.setCancelled(true));
        first.update(TIME);

        assertFalse(first.isRemoved());
        assertFalse(second.isRemoved());
        assertTrue(first.claim(), "the cancelled merge kept the claim on the merging item");
        assertTrue(second.claim(), "the cancelled merge kept the claim on the other item");
    }

    @Test
    public void claimedNeighbourIsNotMerged(Env env) {
        var instance = env.createFlatInstance();
        var spawn = new Pos(0, 42, 0);
        var first = spawnItem(instance, spawn, 1);
        var second = spawnItem(instance, spawn, 1);

        var merges = new AtomicInteger();
        env.process().eventHandler().addListener(EntityItemMergeEvent.class, _ -> merges.incrementAndGet());
        assertTrue(second.claim());
        first.update(TIME);

        assertEquals(0, merges.get(), "an item already claimed by someone else was merged away");
        assertFalse(second.isRemoved());
        assertTrue(first.claim(), "the skipped merge left a claim on the scanning item");
    }

    private static LivingEntity picker(Instance instance, Pos spawn) {
        var entity = new LivingEntity(EntityType.ZOMBIE);
        entity.setCanPickupItem(true);
        entity.setInstance(instance, spawn).join();
        return entity;
    }

    private static ItemEntity spawnItem(Instance instance, Pos spawn, int amount) {
        var item = new ItemEntity(ItemStack.of(Material.DIAMOND, amount));
        item.setInstance(instance, spawn).join();
        return item;
    }
}
