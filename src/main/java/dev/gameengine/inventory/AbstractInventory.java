package dev.gameengine.inventory;

import dev.gameengine.ServerProcess;
import dev.gameengine.Viewable;
import dev.gameengine.entity.Player;
import dev.gameengine.event.EventFilter;
import dev.gameengine.event.EventHandler;
import dev.gameengine.event.EventNode;
import dev.gameengine.event.inventory.InventoryItemChangeEvent;
import dev.gameengine.event.trait.InventoryEvent;
import dev.gameengine.inventory.click.Click;
import dev.gameengine.inventory.click.InventoryClickProcessor;
import dev.gameengine.item.ItemStack;
import dev.gameengine.network.packet.server.play.CloseWindowPacket;
import dev.gameengine.network.packet.server.play.SetSlotPacket;
import dev.gameengine.network.packet.server.play.WindowItemsPacket;
import dev.gameengine.tag.TagHandler;
import dev.gameengine.tag.Taggable;
import dev.gameengine.utils.MathUtils;
import dev.gameengine.utils.validate.Check;
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
 */
public sealed abstract class AbstractInventory implements InventoryClickHandler, Taggable, Viewable, EventHandler<InventoryEvent>
        permits Inventory, PlayerInventory {

    private static final VarHandle ITEM_UPDATER = MethodHandles.arrayElementVarHandle(ItemStack[].class);

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
    protected synchronized void bindProcess(ServerProcess process) {
        if (this.process == process) return;
        Check.stateCondition(this.process != null, "Inventory is already bound to another server process");
        this.process = process;
        this.eventNode = process.eventHandler().map(this, EventFilter.INVENTORY);
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
        ItemStack cursorItem = player.getInventory().getCursorItem();
        player.getInventory().setCursorItem(ItemStack.AIR);

        if (!cursorItem.isAir()) {
            // ALL_OR_NOTHING: keep the item on the cursor when it can neither be dropped nor stored,
            // rather than destroying it
            if (!player.dropItem(cursorItem) && !player.getInventory().addItemStack(cursorItem)) {
                player.getInventory().setCursorItem(cursorItem);
            }
        }

        if (player.didCloseInventory()) {
            player.sendPacket(new CloseWindowPacket(getWindowId()));
        }

        return true;
    }

    /**
     * A click is a read-modify-write cycle over the clicked slots, so it has to run inside the same monitor as
     * {@link #setItemStack(int, ItemStack)} and {@link #processItemStack(ItemStack, TransactionType, TransactionOption)}.
     * Otherwise two viewers of the same inventory can read the same slot and each of them takes a copy of it.
     */
    @Override
    public boolean handleClick(Player player, Click click) {
        synchronized (this) {
            return InventoryClickHandler.super.handleClick(player, click);
        }
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
     * @param sendPacket whether to send packets
     */
    public void setItemStack(int slot, ItemStack itemStack, boolean sendPacket) {
        Check.argCondition(!MathUtils.isBetween(slot, 0, getSize() - 1), // Subtract 1 because MathUtils is <= max, instead of strictly less than
                "Inventory does not have the slot " + slot);

        ItemStack previous;
        synchronized (this) {
            previous = itemStacks[slot];
            if (itemStack.equals(previous)) return; // Avoid sending updates if the item has not changed
            UNSAFE_itemInsert(slot, itemStack, previous, sendPacket);
        }
        // An inventory that has never been shown to a player belongs to no process, so there is
        // nowhere to dispatch the event to.
        final ServerProcess process = this.process;
        if (process != null)
            process.eventHandler().call(new InventoryItemChangeEvent(this, slot, previous, itemStack));
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
     *
     * @param itemStack the item to add
     * @param option    the transaction option
     * @return true if the item has been successfully added, false otherwise
     */
    public <T> T addItemStack(ItemStack itemStack, TransactionOption<T> option) {
        return processItemStack(itemStack, TransactionType.ADD, option);
    }

    public boolean addItemStack(ItemStack itemStack) {
        return addItemStack(itemStack, TransactionOption.ALL_OR_NOTHING);
    }

    /**
     * Adds {@link ItemStack}s to the inventory and sends relevant updates to the viewer(s).
     *
     * @param itemStacks items to add
     * @param option     the transaction option
     * @return the operation results
     */
    public <T> List<T> addItemStacks(List<ItemStack> itemStacks,
                                     TransactionOption<T> option) {
        return processItemStacks(itemStacks, TransactionType.ADD, option);
    }

    /**
     * Takes an {@link ItemStack} from the inventory and sends relevant update to the viewer(s).
     *
     * @param itemStack the item to take
     * @return true if the item has been successfully fully taken, false otherwise
     */
    public <T> T takeItemStack(ItemStack itemStack, TransactionOption<T> option) {
        return processItemStack(itemStack, TransactionType.TAKE, option);
    }

    /**
     * Takes {@link ItemStack}s from the inventory and sends relevant updates to the viewer(s).
     *
     * @param itemStacks items to take
     * @return the operation results
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
     * <p>
     * Be aware that the returned array does not need to be the original one,
     * meaning that modifying it directly may not work.
     *
     * @return an array containing all the inventory's items
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
     * Gets the size of the "inner inventory" (which includes only "usable" slots).
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
