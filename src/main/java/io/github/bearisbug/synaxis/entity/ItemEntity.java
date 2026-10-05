package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.entity.metadata.item.ItemEntityMeta;
import io.github.bearisbug.synaxis.event.entity.EntityItemMergeEvent;
import io.github.bearisbug.synaxis.instance.EntityTracker;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.thread.Acquirable;
import io.github.bearisbug.synaxis.utils.MathUtils;
import io.github.bearisbug.synaxis.timer.Cooldown;
import io.github.bearisbug.synaxis.timer.TimeUnit;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.temporal.TemporalUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents an item on the ground.
 */
public class ItemEntity extends Entity {

    /**
     * Used to slow down the merge check delay
     */
    private static Duration mergeDelay = Duration.of(10, TimeUnit.SERVER_TICK);

    /**
     * The last time that this item has checked his neighbors for merge
     */
    private long lastMergeCheck;

    private ItemStack itemStack;

    private boolean pickable = true;
    private boolean mergeable = true;
    private float mergeRange = 1;
    private boolean previousOnGround = false;

    // Spawn time in System#nanoTime
    private long spawnTime;
    // pickup delay in nanos
    private long pickupDelay;
    // Taken by whoever is about to consume this item. #isPickable and #remove alone are not enough:
    // the pickup and merge paths fire a cancellable event between the check and the removal, so two
    // tick threads scanning the same item would both get to hand out its stack.
    private final AtomicBoolean claimed = new AtomicBoolean();

    @SuppressWarnings("this-escape") // deliberate self registration during construction
    public ItemEntity(ItemStack itemStack) {
        super(EntityType.ITEM);
        setItemStack(itemStack);
        setBoundingBox(0.25f, 0.25f, 0.25f);
    }

    /**
     * Gets the update option for the merging feature.
     *
     * @return the merge update option
     */
    @Nullable
    public static Duration getMergeDelay() {
        return mergeDelay;
    }

    /**
     * Changes the merge delay.
     * Can be set to null to entirely remove the delay.
     *
     * @param delay the new merge delay
     */
    public static void setMergeDelay(@Nullable Duration delay) {
        ItemEntity.mergeDelay = delay;
    }

    @Override
    public void update(long time) {
        if (isMergeable() && isPickable() &&
                (mergeDelay == null || !Cooldown.hasCooldown(time, lastMergeCheck, mergeDelay))) {
            this.lastMergeCheck = time;

            this.instance.getEntityTracker().nearbyEntities(position, mergeRange,
                    EntityTracker.Target.ITEMS, itemEntity -> {
                        if (itemEntity == this) return;
                        if (!itemEntity.isPickable() || !itemEntity.isMergeable()) return;

                        final ItemStack itemStackEntity = itemEntity.getItemStack();
                        final boolean canStack = itemStack.isSimilar(itemStackEntity);

                        if (!canStack) return;
                        final int totalAmount = itemStack.amount() + itemStackEntity.amount();
                        if (!MathUtils.isBetween(totalAmount, 0, itemStack.maxStackSize())) return;
                        // Both sides are consumed by the merge, and both sides scan each other. Claiming
                        // them in a fixed order stops two neighbours from each absorbing the other.
                        final ItemEntity lowest = getEntityId() < itemEntity.getEntityId() ? this : itemEntity;
                        final ItemEntity highest = lowest == this ? itemEntity : this;
                        if (!lowest.claim()) return;
                        if (!highest.claim()) {
                            lowest.releaseClaim();
                            return;
                        }
                        // Read before the claims, the neighbour's stack may be stale: on its own tick thread it may
                        // have absorbed an item and let its claim go since. Under both claims neither stack changes
                        // any more, so a stale read gives this merge up until the next check
                        if (itemEntity.getItemStack() != itemStackEntity) {
                            lowest.releaseClaim();
                            highest.releaseClaim();
                            return;
                        }
                        final ItemStack result = itemStack.withAmount(totalAmount);
                        EntityItemMergeEvent entityItemMergeEvent = new EntityItemMergeEvent(this, itemEntity, result);
                        callCancellableEvent(entityItemMergeEvent, () -> {
                            setItemStack(entityItemMergeEvent.getResult());
                            itemEntity.removeConsumed();
                        });
                        // This entity survives the merge, the absorbed one stays claimed for good
                        releaseClaim();
                        if (entityItemMergeEvent.isCancelled()) itemEntity.releaseClaim();
                    });
        }
    }

    @Override
    public void movementTick() {
        super.movementTick();

        if (!previousOnGround && onGround) {
            synchronizePosition();
            sendPacketToViewers(getVelocityPacket());
        }

        previousOnGround = onGround;
    }

    @Override
    public void spawn() {
        this.spawnTime = System.nanoTime();
    }

    @Override
    public ItemEntityMeta getEntityMeta() {
        return (ItemEntityMeta) super.getEntityMeta();
    }

    /**
     * Gets the item stack on ground.
     *
     * @return the item stack
     */
    public ItemStack getItemStack() {
        return itemStack;
    }

    /**
     * Changes the item stack on ground.
     *
     * @param itemStack the item stack
     */
    public void setItemStack(ItemStack itemStack) {
        this.itemStack = itemStack;
        getEntityMeta().setItem(itemStack);
    }

    /**
     * Gets if the item is currently pickable.
     * <p>
     * {@link #setPickable(boolean)} needs to be true, the delay {@link #getPickupDelay()}
     * to be long gone, and the item must not have been removed.
     *
     * @return true if the item is pickable, false otherwise
     */
    public boolean isPickable() {
        return pickable && getTimeSinceSpawn() >= pickupDelay && !isRemoved();
    }

    /**
     * Claims this item for the caller, so that nothing else may consume it until the claim is released.
     *
     * @return true if the claim was taken, false if someone else already holds it
     */
    @ApiStatus.Internal
    public boolean claim() {
        return claimed.compareAndSet(false, true);
    }

    /**
     * Releases a claim taken by {@link #claim()} because the consumption did not happen.
     */
    @ApiStatus.Internal
    public void releaseClaim() {
        this.claimed.set(false);
    }

    /**
     * Removes this item for whoever consumed it under {@link #claim()}, at once on a thread that owns it.
     * A pickup or a merge may run on the tick thread of a neighbouring grid cell: the removal then goes to
     * this item's own scheduler and runs on the thread that owns it, at its next tick
     * ({@code docs/DESIGN-PROD.md} {@code API-PROD-016}). The claim keeps every other picker and merger off
     * it until then.
     */
    void removeConsumed() {
        if (acquirable().isOwned()) remove();
        else scheduleNextTick(Entity::remove);
    }

    /**
     * Makes the item pickable.
     *
     * @param pickable true to make the item pickable, false otherwise
     */
    public void setPickable(boolean pickable) {
        this.pickable = pickable;
    }

    /**
     * Gets if the item is mergeable.
     *
     * @return true if the entity is mergeable, false otherwise
     */
    public boolean isMergeable() {
        return mergeable;
    }

    /**
     * When set to true, close {@link ItemEntity} will try to merge together as a single entity
     * when their {@link #getItemStack()} is similar and allowed to stack together.
     *
     * @param mergeable should the entity merge with other {@link ItemEntity}
     */
    public void setMergeable(boolean mergeable) {
        this.mergeable = mergeable;
    }

    /**
     * Gets the merge range.
     *
     * @return the merge range
     */
    public float getMergeRange() {
        return mergeRange;
    }

    /**
     * Changes the merge range.
     *
     * @param mergeRange the merge range
     */
    public void setMergeRange(float mergeRange) {
        this.mergeRange = mergeRange;
    }

    /**
     * Gets the pickup delay in milliseconds, defined by {@link #setPickupDelay(Duration)}.
     *
     * @return the pickup delay
     */
    public long getPickupDelay() {
        return pickupDelay;
    }

    /**
     * Sets the pickup delay of the ItemEntity.
     *
     * @param delay        the pickup delay
     * @param temporalUnit the unit of the delay
     */
    public void setPickupDelay(long delay, TemporalUnit temporalUnit) {
        setPickupDelay(Duration.of(delay, temporalUnit));
    }

    /**
     * Sets the pickup delay of the ItemEntity.
     *
     * @param delay the pickup delay
     */
    public void setPickupDelay(Duration delay) {
        this.pickupDelay = delay.toMillis();
    }
    
    /**
     * Used to know if the ItemEntity can be picked up.
     *
     * @return the elapsed time in milliseconds since this entity has spawned
     */
    public long getTimeSinceSpawn() {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - spawnTime);
    }

    @ApiStatus.Experimental
    @SuppressWarnings("unchecked")
    @Override
    public Acquirable<? extends ItemEntity> acquirable() {
        return (Acquirable<? extends ItemEntity>) super.acquirable();
    }
}
