package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.entity.EntityDespawnEvent;
import io.github.bearisbug.synaxis.event.entity.EntityItemMergeEvent;
import io.github.bearisbug.synaxis.event.item.PickupItemEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.testing.Env;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An item consumed across a grid cell border ({@code docs/DESIGN-PROD.md} {@code API-PROD-016}, §28.2): 4 tick
 * threads on a grid of 2 by 2 chunk cells, x = 32 is the border of cell (0, 0) and cell (1, 0), so whatever
 * stands at x = 31.7 and an item at x = 32.3 are ticked by two tick threads. The item is removed on its own thread.
 */
public class ItemEntityCrossCellConcurrencyTest {

    private static final double WEST = 31.7, EAST = 32.3;

    private final List<String> removedOffTheirThread = new CopyOnWriteArrayList<>();
    private Env env;

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    public void anItemPickedUpAcrossACellBorderIsRemovedOnItsOwnThread() {
        final Instance instance = gridInstance();
        final LivingEntity picker = new LivingEntity(EntityType.ZOMBIE);
        picker.setCanPickupItem(true);
        picker.setInstance(instance, at(WEST)).join();
        final ItemEntity item = item(instance, EAST);
        final List<Boolean> itemOwnedByThePicker = new CopyOnWriteArrayList<>();
        env.process().eventHandler().addListener(PickupItemEvent.class,
                event -> itemOwnedByThePicker.add(event.getItemEntity().acquirable().isOwned()));

        tickUntil(item::isRemoved);

        assertEquals(List.of(false), itemOwnedByThePicker, "one pickup, of an item another tick thread owns");
        assertTrue(item.isRemoved(), "the item picked up is still in the world");
        assertEquals(List.of(), removedOffTheirThread, "an item was removed on a thread that does not own it");
    }

    @Test
    public void anItemMergedAcrossACellBorderIsRemovedOnItsOwnThread() {
        final Instance instance = gridInstance();
        final ItemEntity west = item(instance, WEST);
        final ItemEntity east = item(instance, EAST);
        final List<Boolean> mergedOwnedByTheMerger = new CopyOnWriteArrayList<>();
        env.process().eventHandler().addListener(EntityItemMergeEvent.class,
                event -> mergedOwnedByTheMerger.add(event.getMerged().acquirable().isOwned()));

        tickUntil(() -> west.isRemoved() || east.isRemoved());

        assertEquals(List.of(false), mergedOwnedByTheMerger, "one merge, of an item another tick thread owns");
        assertNotEquals(west.isRemoved(), east.isRemoved(), "exactly one side of the merge is removed");
        assertEquals(2, (west.isRemoved() ? east : west).getItemStack().amount());
        assertEquals(List.of(), removedOffTheirThread, "an item was removed on a thread that does not own it");
    }

    private Instance gridInstance() {
        env = Env.create(settings -> settings.dispatcherThreads(4)
                .threadProvider(ServerConfig.ThreadProviderKind.GRID)
                .threadGridExponent(1));
        env.process().eventHandler().addListener(EntityDespawnEvent.class, event -> {
            if (event.getEntity() instanceof ItemEntity item && !item.acquirable().isOwned())
                removedOffTheirThread.add(Thread.currentThread().getName());
        });
        return env.createFlatInstance();
    }

    private void tickUntil(BooleanSupplier done) {
        for (int tick = 0; tick < 20 && !done.getAsBoolean(); tick++) env.tick();
    }

    private static Pos at(double x) {
        return new Pos(x, 40, 8.5);
    }

    private static ItemEntity item(Instance instance, double x) {
        final ItemEntity item = new ItemEntity(ItemStack.of(Material.DIAMOND, 1));
        item.setInstance(instance, at(x)).join();
        return item;
    }
}
