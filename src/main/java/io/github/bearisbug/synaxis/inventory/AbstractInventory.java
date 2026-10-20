package io.github.bearisbug.synaxis.inventory;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.Viewable;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.EventHandler;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.inventory.InventoryClickEvent;
import io.github.bearisbug.synaxis.event.inventory.InventoryItemChangeEvent;
import io.github.bearisbug.synaxis.event.item.EntityEquipEvent;
import io.github.bearisbug.synaxis.event.trait.InventoryEvent;
import io.github.bearisbug.synaxis.inventory.click.Click;
import io.github.bearisbug.synaxis.inventory.click.InventoryClickProcessor;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.network.packet.server.play.CloseWindowPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetSlotPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.WindowItemsPacket;
import io.github.bearisbug.synaxis.tag.TagHandler;
import io.github.bearisbug.synaxis.tag.Taggable;
import io.github.bearisbug.synaxis.utils.MathUtils;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.UnaryOperator;

/**
 * Represents an inventory where items can be modified/retrieved.
 * <p>
 * Slots are written inside this inventory's monitor. A click ({@link #handleClick(Player, Click)}) holds the clicked
 * inventory's monitor and then the clicking player's one; the {@link InventoryClickEvent}s and
 * {@link InventoryItemChangeEvent}s it raises are dispatched after the whole click, cursor included, has been committed
 * and both monitors are released. Two kinds of events are still dispatched inside a monitor: {@link EntityEquipEvent}
 * (inside the player inventory's one, before the click is committed) and the {@link InventoryItemChangeEvent}s of
 * {@link #processItemStack(ItemStack, TransactionType, TransactionOption)}, {@link #replaceItemStack(int, UnaryOperator)}
 * and {@link #clear()}. Their listeners must not synchronously write another inventory or another player's inventory:
 * two tick threads doing that crosswise wait for each other's monitor forever. Defer such a write to a later tick, for
 * example with {@link Player#scheduleNextTick(java.util.function.Consumer)}.
 */
public sealed abstract class AbstractInventory implements InventoryClickHandler, Taggable, Viewable, EventHandler<InventoryEvent>
        permits Inventory, PlayerInventory {

    private static final VarHandle ITEM_UPDATER = MethodHandles.arrayElementVarHandle(ItemStack[].class);

    // Events raised by the click this thread is handling, dispatched once the click has been committed
    private static final ThreadLocal<@Nullable List<Runnable>> PENDING_CLICK_EVENTS = new ThreadLocal<>();

    private final int size;
    protected final ItemStack[] itemStacks;

    // the click processor which process all the clicks in the inventory
    protected final InventoryClickProcessor clickProcessor = new InventoryClickProcessor();

    private final TagHandler tagHandler = TagHandler.newHandler();

    // the players currently viewing this inventory
    protected final Set<Player> viewers = new CopyOnWriteArraySet<>();
    protected final Set<Player> unmodifiableViewers = Collections.unmodifiableSet(viewers);

    // Bound to a process when the inventory is first shown to a player.
    private volatile @Nullable ServerProcess process;
    // the local event node filtered to this inventory
    private volatile @Nullable EventNode<InventoryEvent> eventNode;

    protected AbstractInventory(int size) {
        this.size = size;
        this.itemStacks = new ItemStack[getSize()];
        Arrays.fill(itemStacks, ItemStack.AIR);
    }

    /**
     * Creates an inventory already bound to a server process, so that its local event node exists
     * before the inventory is shown to anyone.
     */
    @SuppressWarnings("this-escape") // the inventory is fully sized before it registers itself
    protected AbstractInventory(ServerProcess process, int size) {
        this(size);
        bindProcess(process);
    }

    /**
     * Binds this inventory to a server process, creating its local event node. An inventory is bound
     * to the process of the first player it is shown to.
     */
    @ApiStatus.Internal
    protected void bindProcess(ServerProcess process) {
        // Opening an inventory that is already bound must not wait for its monitor, which a click on it may hold
        if (this.process == process) return;
        synchronized (this) {
            if (this.process == process) return;
            Check.stateCondition(this.process != null, "Inventory is already bound to another server process");
            this.eventNode = process.eventHandler().map(this, EventFilter.INVENTORY);
            this.process = process;
        }
    }

    /**
     * Gets the server process this inventory belongs to, {@code null} until it is shown to a player.
     *
     * @return the server process
     */
    public @UnknownNullability ServerProcess process() {
        return process;
    }

    /**
     * Gets this window id.
     * <p>
     * This is the id that the client will send to identify the affected inventory, mostly used by packets.
     *
     * @return the window id
     */
    public abstract byte getWindowId();

    @Override
    public Set<? extends Player> getViewers() {
        return unmodifiableViewers;
    }

    @Override
    public boolean addViewer(Player player) {
        bindProcess(player.process());
        if (!this.viewers.add(player)) return false;

        update(player);
        return true;
    }

    @Override
    public boolean removeViewer(Player player) {
        if (!this.viewers.remove(player)) return false;

        // Drop cursor item when closing inventory
        player.dropCursorItem();

        // A close the server started has to reach the client, one the client made itself is not answered
        if (player.didCloseInventory()) {
            player.sendPacket(new CloseWindowPacket(getWindowId()));
        }

        return true;
    }

    /**
     * A click is a read-modify-write cycle over the clicked slots, so it has to run inside the same monitor as
     * {@link #setItemStack(int, ItemStack)} and {@link #processItemStack(ItemStack, TransactionType, TransactionOption)}.
     * Otherwise two viewers of the same inventory can read the same slot and each of them takes a copy of it.
     * A click on a container reads and writes the clicking player's inventory as well (cursor, shift-click
     * target), so that monitor is held for the whole cycle too: a container is always locked before a player
     * inventory, never the other way around.
     * <p>
     * The slots are written before the cursor. The events the click raises are therefore held back and dispatched,
     * in the order they were raised, once the click has been committed and both monitors are released: a listener
     * that closes or opens an inventory then drops the committed cursor, not the one the click is about to replace.
     */
    @Override
    public boolean handleClick(Player player, Click click) {
        final boolean outermost = PENDING_CLICK_EVENTS.get() == null;
        if (outermost) PENDING_CLICK_EVENTS.set(new ArrayList<>());
        try {
            synchronized (this) {
                synchronized (player.getInventory()) {
                    return InventoryClickHandler.super.handleClick(player, click);
                }
            }
        } finally {
            if (outermost) {
                // Taken off the thread first: what the listeners write is dispatched straight away
                final List<Runnable> pending = Objects.requireNonNull(PENDING_CLICK_EVENTS.get());
                PENDING_CLICK_EVENTS.remove();
                pending.forEach(Runnable::run);
            }
        }
    }

    /**
     * Dispatches an event raised by an inventory operation. While this thread is handling a click the event is held
     * back until the click has been committed, see {@link #handleClick(Player, Click)}; otherwise it is dispatched now.
     *
     * @param process the process whose event handler receives the event
     * @param event   the event to dispatch
     */
    @ApiStatus.Internal
    public static void dispatch(ServerProcess process, Event event) {
        final List<Runnable> pending = PENDING_CLICK_EVENTS.get();
        if (pending != null) pending.add(() -> process.eventHandler().call(event));
        else process.eventHandler().call(event);
    }

    /**
     * Holds {@code action} back until the click this thread is handling has been committed, behind the events it
     * raised so far, see {@link #handleClick(Player, Click)}.
     *
     * @param action what has to see the committed click
     * @return true if it was held back, false if this thread handles no click and the caller runs it now
     */
    @ApiStatus.Internal
    public static boolean deferredUntilTheClickCommits(Runnable action) {
        final List<Runnable> pending = PENDING_CLICK_EVENTS.get();
        if (pending == null) return false;
        pending.add(action);
        return true;
    }

    /**
     * Sets an {@link ItemStack} at the specified slot and send relevant update to the viewer(s).
     *
     * @param slot      the slot to set the item
     * @param itemStack the item to set
     */
    public void setItemStack(int slot, ItemStack itemStack) {
        setItemStack(slot, itemStack, true);
    }

    /**
     * Sets an {@link ItemStack} at the specified slot and send relevant update to the viewer(s).
     *
     * @param slot       the slot to set the item
     * @param itemStack  the item to set
     * @param sendPacket false to skip only the slot update packet to the viewers of this inventory: the
     *                   {@link InventoryItemChangeEvent} is dispatched all the same, and on a {@link PlayerInventory}
     *                   an equipment slot still calls the {@link EntityEquipEvent}, updates the attributes and
     *                   syncs the equipment; call {@link #update()} once a batch of writes is done
     */
    public void setItemStack(int slot, ItemStack itemStack, boolean sendPacket) {
        Check.argCondition(!MathUtils.isBetween(slot, 0, getSize() - 1), // Subtract 1 because MathUtils is <= max, instead of strictly less than
                "Inventory does not have the slot " + slot);

        ItemStack previous;
        ItemStack inserted;
        synchronized (this) {
            previous = itemStacks[slot];
            if (itemStack.equals(previous)) return; // Avoid sending updates if the item has not changed
            UNSAFE_itemInsert(slot, itemStack, previous, sendPacket);
            // The insertion may store something else than requested (EntityEquipEvent on a player inventory)
            inserted = itemStacks[slot];
        }
        // An inventory that has never been shown to a player belongs to no process, so there is
        // nowhere to dispatch the event to.
        final ServerProcess process = this.process;
        if (process != null)
            dispatch(process, new InventoryItemChangeEvent(this, slot, previous, inserted));
    }

    protected void UNSAFE_itemInsert(int slot, ItemStack item, ItemStack previous, boolean sendPacket) {
        ITEM_UPDATER.setVolatile(itemStacks, slot, item);
        if (sendPacket) sendSlotRefresh(slot, item);
    }

    public void sendSlotRefresh(int slot, ItemStack item) {
        sendPacketToViewers(new SetSlotPacket(getWindowId(), 0, (short) slot, item));
    }

    public synchronized <T> T processItemStack(ItemStack itemStack,
                                               TransactionType type,
                                               TransactionOption<T> option) {
        return option.fill(type, this, itemStack);
    }

    /**
     * Processes each item of {@code itemStacks} in order, as {@link #processItemStack(ItemStack, TransactionType,
     * TransactionOption)} would, inside this inventory's monitor.
     * <p>
     * Each item is decided on its own: the list is not one transaction. {@link TransactionOption#ALL_OR_NOTHING} keeps
     * a single item from being partly moved, but the items before a refused one stay moved, and
     * {@link TransactionOption#DRY_RUN} checks every item against the unchanged inventory, without counting the items
     * before it. To move a whole list or nothing, hold this inventory's monitor ({@code synchronized (inventory)}) and
     * check the free room yourself before writing.
     *
     * @param itemStacks the items to process
     * @param type       the transaction type
     * @param option     the transaction option
     * @param <T>        the result type of {@code option}
     * @return one result per item, in the order of {@code itemStacks}
     */
    public synchronized <T> List<T> processItemStacks(List<ItemStack> itemStacks,
                                                      TransactionType type,
                                                      TransactionOption<T> option) {
        List<T> result = new ArrayList<>(itemStacks.size());
        itemStacks.forEach(itemStack -> {
            T transactionResult = processItemStack(itemStack, type, option);
            result.add(transactionResult);
        });
        return result;
    }

    /**
     * Adds an {@link ItemStack} to the inventory and sends relevant update to the viewer(s).
     * <p>
     * The slots from 0 up to {@link #getInnerSize()}, exclusive, take it, the result and output slots of a type that
     * has them included: slot 0 of a {@code CRAFTING} inventory, the slots from {@link InventoryType#getInputSlotEnd()}
     * on of a {@code FURNACE} or an {@code ANVIL}. To fill only the input slots, pick them between
     * {@link InventoryType#getInputSlotStart()} and {@link InventoryType#getInputSlotEnd()} and write them.
     *
     * @param itemStack the item to add
     * @param option    the transaction option
     * @param <T>       the result type of {@code option}
     * @return the result of {@code option}, see {@link TransactionOption}
     */
    public <T> T addItemStack(ItemStack itemStack, TransactionOption<T> option) {
        return processItemStack(itemStack, TransactionType.ADD, option);
    }

    /**
     * Adds an {@link ItemStack} to the inventory, all of it or nothing, see
     * {@link #addItemStack(ItemStack, TransactionOption)}.
     *
     * @param itemStack the item to add
     * @return true if the whole item was added
     */
    public boolean addItemStack(ItemStack itemStack) {
        return addItemStack(itemStack, TransactionOption.ALL_OR_NOTHING);
    }

    /**
     * Adds {@link ItemStack}s to the inventory and sends relevant updates to the viewer(s), in the slots
     * {@link #addItemStack(ItemStack, TransactionOption)} fills.
     * <p>
     * Each item is added on its own, the list is not one transaction: see
     * {@link #processItemStacks(List, TransactionType, TransactionOption)}.
     *
     * @param itemStacks items to add
     * @param option     the transaction option
     * @param <T>        the result type of {@code option}
     * @return one result of {@code option} per item, in the order of {@code itemStacks}
     */
    public <T> List<T> addItemStacks(List<ItemStack> itemStacks,
                                     TransactionOption<T> option) {
        return processItemStacks(itemStacks, TransactionType.ADD, option);
    }

    /**
     * Takes an {@link ItemStack} from the inventory and sends relevant update to the viewer(s).
     *
     * @param itemStack the item to take
     * @param option    the transaction option
     * @param <T>       the result type of {@code option}
     * @return the result of {@code option}, see {@link TransactionOption}
     */
    public <T> T takeItemStack(ItemStack itemStack, TransactionOption<T> option) {
        return processItemStack(itemStack, TransactionType.TAKE, option);
    }

    /**
     * Takes {@link ItemStack}s from the inventory and sends relevant updates to the viewer(s).
     * <p>
     * Each item is taken on its own, the list is not one transaction: see
     * {@link #processItemStacks(List, TransactionType, TransactionOption)}.
     *
     * @param itemStacks items to take
     * @param option     the transaction option
     * @param <T>        the result type of {@code option}
     * @return one result of {@code option} per item, in the order of {@code itemStacks}
     */
    public <T> List<T> takeItemStacks(List<ItemStack> itemStacks,
                                      TransactionOption<T> option) {
        return processItemStacks(itemStacks, TransactionType.TAKE, option);
    }

    public synchronized void replaceItemStack(int slot, UnaryOperator<ItemStack> operator) {
        var currentItem = getItemStack(slot);
        setItemStack(slot, operator.apply(currentItem));
    }

    /**
     * Clears the inventory and send relevant update to the viewer(s).
     */
    public synchronized void clear() {
        // Clear the item array
        for (int i = 0; i < size; i++) {
            setItemStack(i, ItemStack.AIR, false);
        }
        // Send the cleared inventory to viewers
        update();
    }

    /**
     * Refreshes the inventory for all viewers.
     */
    public void update() {
        this.viewers.forEach(this::update);
    }

    /**
     * Refreshes the inventory for a specific viewer.
     *
     * @param player the player to update the inventory for
     */
    public void update(Player player) {
        player.sendPacket(new WindowItemsPacket(getWindowId(), 0, List.of(itemStacks), player.getInventory().getCursorItem()));
    }

    /**
     * Gets the {@link ItemStack} at the specified slot.
     *
     * @param slot the slot to check
     * @return the item in the slot {@code slot}
     */
    public ItemStack getItemStack(int slot) {
        return (ItemStack) ITEM_UPDATER.getVolatile(itemStacks, slot);
    }

    /**
     * Gets all the {@link ItemStack} in the inventory.
     *
     * @return a copy of the inventory's items; writing to it does not change the inventory
     */
    public ItemStack[] getItemStacks() {
        return itemStacks.clone();
    }

    /**
     * Gets the size of the inventory.
     *
     * @return the inventory's size
     */
    public int getSize() {
        return size;
    }

    /**
     * Gets the size of the "inner inventory": {@link #getSize()} for an {@link Inventory}, the 36 slots without the
     * equipment and crafting slots for a {@link PlayerInventory}.
     *
     * @return inner inventory's size
     */
    public int getInnerSize() {
        return getSize();
    }

    /**
     * Places all the items of {@code itemStacks} into the internal array.
     *
     * @param itemStacks the array to copy the content from
     * @throws IllegalArgumentException if the size of the array is not equal to {@link #getSize()}
     * @throws NullPointerException     if {@code itemStacks} contains one null element or more
     */
    public void copyContents(ItemStack[] itemStacks) {
        Check.argCondition(itemStacks.length != getSize(),
                "The size of the array has to be of the same size as the inventory: " + getSize());

        for (int i = 0; i < itemStacks.length; i++) {
            final ItemStack itemStack = itemStacks[i];
            Objects.requireNonNull(itemStack, "The item array cannot contain any null element!");
            setItemStack(i, itemStack);
        }
    }

    @Override
    public TagHandler tagHandler() {
        return tagHandler;
    }

    @Override
    public @UnknownNullability EventNode<InventoryEvent> eventNode() {
        return eventNode;
    }
}
