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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Items consumed across a grid cell border by two tick threads at once ({@code docs/DESIGN-PROD.md} §28.2, security
 * review 2026-10 S3-01; the probe of commit {@code 7219ce342d}). 4 tick threads on a grid of 2 by 2 chunk cells,
 * x = 32 is the border of cell (0, 0) and cell (1, 0), so an entity at x = 31.7 and one at x = 32.3 or 32.9 are
 * ticked by two tick threads in the same tick.
 * <p>
 * The merge holds the merger between its first read of the neighbour's stack and its claims while the neighbour
 * absorbs a third item on its own thread and lets go of its claim: a merge that wrote what it read before the
 * claims would drop the third item. The pickup holds the winner of the claim inside its event until the other
 * picker has tried, and expects one pickup.
 */
public class GridItemRaceTest {

    private static final double WEST = 31.7, EAST = 32.3, FAR_EAST = 32.9;
    private static final long HOLD_SECONDS = 5;

    private final List<String> removedOffTheirThread = new CopyOnWriteArrayList<>();
    private Env env;

    @AfterEach
    void tearDown() {
        if (env != null) env.process().stop();
    }

    /** The neighbour: holds the merger's first read of its stack until it absorbed the third item and let go of its claim. */
    private static final class Neighbour extends ItemEntity {
        volatile ItemEntity merger;
        final CountDownLatch read = new CountDownLatch(1);
        final CountDownLatch absorbedAndReleased = new CountDownLatch(1);
        final AtomicBoolean held = new AtomicBoolean();
        final AtomicBoolean absorbed = new AtomicBoolean();

        Neighbour(ItemStack itemStack) {
            super(itemStack);
        }

        @Override
        public ItemStack getItemStack() {
            final ItemStack seen = super.getItemStack();
            final ItemEntity reader = merger;
            if (reader != null && Thread.currentThread() == reader.acquirable().assignedThread() && held.compareAndSet(false, true)) {
                read.countDown();
                hold(absorbedAndReleased);
            }
            return seen;
        }

        @Override
        public void update(long time) {
            // Scans its neighbours only once the merger holds what it read of this one
            hold(read);
            super.update(time);
        }

        @Override
        public void releaseClaim() {
            super.releaseClaim();
            if (absorbed.get()) absorbedAndReleased.countDown();
        }
    }

    /** The third item: never scans, only absorbed. */
    private static final class Passive extends ItemEntity {
        Passive(ItemStack itemStack) {
            super(itemStack);
        }

        @Override
        public void update(long time) {
        }
    }

    /** An item that counts the claims tried on it, and lets a pickup event wait for the second one. */
    private static final class Contested extends ItemEntity {
        final AtomicInteger attempts = new AtomicInteger();
        final CountDownLatch bothTried = new CountDownLatch(2);

        Contested(ItemStack itemStack) {
            super(itemStack);
        }

        @Override
        public boolean claim() {
            final boolean taken = super.claim();
            attempts.incrementAndGet();
            bothTried.countDown();
            return taken;
        }
    }

    @Test
    public void aMergeAcrossACellBorderKeepsEveryItem() {
        final Instance instance = gridInstance();
        // a + b fits a stack and so does b + c, a + b + c does not: once the neighbour holds b + c it no longer
        // tries the merger, so the only merge left between them is the merger's, on what it read before
        final int a = 40, b = 10, c = 20;
        final ItemEntity merger = new ItemEntity(ItemStack.of(Material.DIAMOND, a));
        final Neighbour neighbour = new Neighbour(ItemStack.of(Material.DIAMOND, b));
        final Passive third = new Passive(ItemStack.of(Material.DIAMOND, c));
        neighbour.merger = merger;
        env.process().eventHandler().addListener(EntityItemMergeEvent.class, event -> {
            final ItemEntity survivor = event.getEntity();
            final ItemEntity merged = event.getMerged();
            if (survivor == neighbour && merged == third) {
                neighbour.absorbed.set(true);
                return;
            }
            if (survivor == merger && merged == neighbour) return;
            event.setCancelled(true);
        });
        merger.setInstance(instance, at(WEST)).join();
        neighbour.setInstance(instance, at(EAST)).join();
        third.setInstance(instance, at(FAR_EAST)).join();

        tickUntil(() -> neighbour.isRemoved() && third.isRemoved(), 40);
        env.tick();

        assertTrue(neighbour.held.get() && neighbour.absorbedAndReleased.getCount() == 0,
                "the neighbour absorbed the third item while the merger held what it had read of it");
        final int onTheGround = instance.getEntities().stream()
                .filter(entity -> entity instanceof ItemEntity item && !item.isRemoved())
                .mapToInt(entity -> ((ItemEntity) entity).getItemStack().amount()).sum();
        assertEquals(a + b + c, onTheGround, "diamonds on the ground after the merges (merger holds "
                + merger.getItemStack().amount() + ")");
        assertEquals(List.of(), removedOffTheirThread, "an item was removed on a thread that does not own it");
    }

    @Test
    public void twoPickersOnTwoTickThreadsPickAnItemUpOnce() {
        final Instance instance = gridInstance();
        final LivingEntity west = picker(instance, WEST);
        picker(instance, FAR_EAST);
        final Contested item = new Contested(ItemStack.of(Material.DIAMOND, 1));
        final List<String> pickups = new CopyOnWriteArrayList<>();
        env.process().eventHandler().addListener(PickupItemEvent.class, event -> {
            pickups.add((event.getLivingEntity() == west ? "west" : "east") + " on " + Thread.currentThread().getName());
            // The winner of the claim stays in its event until the other picker has tried
            hold(item.bothTried, 2);
        });
        item.setInstance(instance, at(EAST)).join();

        tickUntil(item::isRemoved, 20);

        assertTrue(item.attempts.get() >= 2, "both pickers tried the item: " + item.attempts.get() + " claims, " + pickups);
        assertEquals(1, pickups.size(), "pickups of one item: " + pickups);
        assertTrue(item.isRemoved(), "the item picked up is still in the world");
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

    private static LivingEntity picker(Instance instance, double x) {
        final LivingEntity picker = new LivingEntity(EntityType.ZOMBIE);
        picker.setCanPickupItem(true);
        picker.setInstance(instance, at(x)).join();
        return picker;
    }

    private void tickUntil(BooleanSupplier done, int most) {
        for (int tick = 0; tick < most && !done.getAsBoolean(); tick++) env.tick();
    }

    private static void hold(CountDownLatch latch) {
        hold(latch, HOLD_SECONDS);
    }

    private static void hold(CountDownLatch latch, long seconds) {
        try {
            boolean _ = latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static Pos at(double x) {
        return new Pos(x, 40, 8.5);
    }
}
