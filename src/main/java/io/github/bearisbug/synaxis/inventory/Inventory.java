package io.github.bearisbug.synaxis.inventory;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.inventory.click.ClickType;
import io.github.bearisbug.synaxis.inventory.click.InventoryClickResult;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.network.packet.server.play.OpenWindowPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.WindowPropertyPacket;
import io.github.bearisbug.synaxis.utils.validate.Check;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents an inventory which can be viewed by a collection of {@link Player}.
 * <p>
 * You can create one with {@link Inventory#Inventory(InventoryType, String)} or by making your own subclass.
 * It can then be opened using {@link Player#openInventory(Inventory)}.
 * <p>
 * Window ids are not unique per player: see {@link #getWindowId()} before letting a click on an inventory created for
 * each opening carry an action of value.
 */
public non-sealed class Inventory extends AbstractInventory {
    private static final AtomicInteger ID_COUNTER = new AtomicInteger();

    private final byte id;
    private final InventoryType inventoryType;
    private Component title;

    private final int offset;

    @SuppressWarnings("this-escape") // deliberate self registration during construction
    public Inventory(InventoryType inventoryType, Component title) {
        super(inventoryType.getSize());
        this.id = generateId();
        this.inventoryType = inventoryType;
        this.title = title;

        this.offset = getSize();
    }

    public Inventory(InventoryType inventoryType, String title) {
        this(inventoryType, Component.text(title));
    }

    /**
     * Creates an inventory already bound to a server process, see
     * {@link AbstractInventory#AbstractInventory(ServerProcess, int)}.
     */
    @SuppressWarnings("this-escape") // deliberate self registration during construction
    public Inventory(ServerProcess process, InventoryType inventoryType, Component title) {
        super(process, inventoryType.getSize());
        this.id = generateId();
        this.inventoryType = inventoryType;
        this.title = title;

        this.offset = getSize();
    }

    public Inventory(ServerProcess process, InventoryType inventoryType, String title) {
        this(process, inventoryType, Component.text(title));
    }

    private static byte generateId() {
        return (byte) ID_COUNTER.updateAndGet(i -> i + 1 >= 128 ? 1 : i + 1);
    }

    /**
     * Gets the inventory type.
     *
     * @return the inventory type
     */
    public InventoryType getInventoryType() {
        return inventoryType;
    }

    /**
     * Gets the inventory title.
     *
     * @return the inventory title
     */
    public Component getTitle() {
        return title;
    }

    /**
     * Changes the inventory title.
     *
     * @param title the new inventory title
     */
    public void setTitle(Component title) {
        this.title = title;
        // Re-open the inventory
        sendPacketToViewers(new OpenWindowPacket(getWindowId(), getInventoryType().getWindowType(), title));
        // Send inventory items
        update();
    }

    @Override
    public byte getWindowId() {
        return id;
    }

    /**
     * Shows this inventory to {@code player}: sends the window and its items. {@link Player#openInventory(Inventory)}
     * calls it once the player's open inventory is this one, and a subclass may override it to send more packets.
     * Do not call it directly, call {@link Player#openInventory(Inventory)}: the client would show a window the server
     * does not consider open, whose clicks are dropped and whose viewer is never removed.
     *
     * @param player the viewer to add
     * @return true if the player has successfully been added
     * @throws IllegalStateException if the open inventory of {@code player} is not this one
     */
    @Override
    public boolean addViewer(Player player) {
        Check.stateCondition(player.getOpenInventory() != this,
                "Inventory#addViewer does not register the inventory as open; use Player#openInventory(Inventory)");
        bindProcess(player.process());
        if (!this.viewers.add(player)) return false;

        // Also send the open window packet
        player.sendPacket(new OpenWindowPacket(id, getInventoryType().getWindowType(), title));
        update(player);
        return true;
    }

    /**
     * Removes {@code player} from the viewers and drops its cursor item. This does not close the inventory:
     * {@link Player#getOpenInventory()} still returns it and the client keeps the window open. Call
     * {@link Player#closeInventory()} instead, which calls this method.
     *
     * @param player the viewer to remove
     * @return true if the player has successfully been removed
     */
    @Override
    public boolean removeViewer(Player player) {
        if (!super.removeViewer(player)) return false;

        player.getClickPreprocessor().clearCache();
        return true;
    }

    /**
     * Gets the cursor item of a player.
     *
     * @deprecated normal inventories no longer store cursor items
     */
    @Deprecated
    public ItemStack getCursorItem(Player player) {
        return player.getInventory().getCursorItem();
    }

    /**
     * Changes the cursor item of a player.
     *
     * @deprecated normal inventories no longer store cursor items
     */
    @Deprecated
    public void setCursorItem(Player player, ItemStack cursorItem) {
        player.getInventory().setCursorItem(cursorItem);
    }

    /**
     * Sends a window property to all viewers.
     *
     * @param property the property to send
     * @param value    the value of the property
     * @see <a href="https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Protocol#Set_Container_Property">the Minecraft wiki</a>
     */
    protected void sendProperty(InventoryProperty property, short value) {
        sendPacketToViewers(new WindowPropertyPacket(getWindowId(), property.getProperty(), value));
    }

    @Override
    public boolean leftClick(Player player, int slot) {
        final PlayerInventory playerInventory = player.getInventory();
        final ItemStack cursor = playerInventory.getCursorItem();
        final boolean isInWindow = isClickInWindow(slot);
        final int clickSlot = isInWindow ? slot : slot - offset;
        final AbstractInventory clickedInventory = isInWindow ? this : playerInventory;
        final ItemStack clicked = isInWindow ? getItemStack(slot) : playerInventory.getItemStack(clickSlot);
        final InventoryClickResult clickResult = clickProcessor.leftClick(clicked, cursor);
        if (clickResult.isCancel()) {
            updateAll(player);
            return false;
        }
        if (isInWindow) {
            setItemStack(slot, clickResult.getClicked());
        } else {
            playerInventory.setItemStack(clickSlot, clickResult.getClicked());
        }
        playerInventory.setCursorItem(clickResult.getCursor());
        callClickEvent(player, clickedInventory, clickSlot, ClickType.LEFT_CLICK, clicked, cursor);
        return true;
    }

    @Override
    public boolean rightClick(Player player, int slot) {
        final PlayerInventory playerInventory = player.getInventory();
        final ItemStack cursor = playerInventory.getCursorItem();
        final boolean isInWindow = isClickInWindow(slot);
        final int clickSlot = isInWindow ? slot : slot - offset;
        final ItemStack clicked = isInWindow ? getItemStack(slot) : playerInventory.getItemStack(clickSlot);
        final AbstractInventory clickedInventory = isInWindow ? this : playerInventory;
        final InventoryClickResult clickResult = clickProcessor.rightClick(clicked, cursor);
        if (clickResult.isCancel()) {
            updateAll(player);
            return false;
        }
        if (isInWindow) {
            setItemStack(slot, clickResult.getClicked());
        } else {
            playerInventory.setItemStack(clickSlot, clickResult.getClicked());
        }
        playerInventory.setCursorItem(clickResult.getCursor());
        callClickEvent(player, clickedInventory, clickSlot, ClickType.RIGHT_CLICK, clicked, cursor);
        return true;
    }

    @Override
    public boolean shiftClick(Player player, int slot, int button) {
        final PlayerInventory playerInventory = player.getInventory();
        final boolean isInWindow = isClickInWindow(slot);
        final int clickSlot = isInWindow ? slot : slot - offset;
        final ItemStack clicked = isInWindow ? getItemStack(slot) : playerInventory.getItemStack(clickSlot);
        final ItemStack cursor = playerInventory.getCursorItem(); // Isn't used in the algorithm

        InventoryClickResult clickResult;
        if (isInWindow) {
            // The player shift-clicked an item in this GUI into their inventory.
            // Prioritize the hotbar (8->0), then their regular inventory (35->9).
            clickResult = clickProcessor.shiftClick(
                    this, playerInventory,
                    8, -1, -1,
                    player, clickSlot, clicked, cursor);

            if (!clickResult.getClicked().isAir()) {
                // Keep filling with whatever the hotbar could not take
                final InventoryClickResult rest = clickProcessor.shiftClick(
                        this, playerInventory,
                        playerInventory.getInnerSize() - 1, 8, -1,
                        player, clickSlot, clickResult.getClicked(), cursor);
                if (!rest.isCancel()) clickResult = rest;
            }
        } else {
            clickResult = clickProcessor.shiftClick(
                    playerInventory, this,
                    inventoryType.getInputSlotStart(), inventoryType.getInputSlotEnd(), 1,
                    player, clickSlot, clicked, cursor);
        }

        if (clickResult.isCancel()) {
            updateAll(player);
            return false;
        }

        if (isInWindow) {
            setItemStack(slot, clickResult.getClicked());
        } else {
            playerInventory.setItemStack(clickSlot, clickResult.getClicked());
        }

        updateAll(player); // FIXME: currently not properly client-predicted
        playerInventory.setCursorItem(clickResult.getCursor());
        return true;
    }

    @Override
    public boolean changeHeld(Player player, int slot, int key) {
        final int convertedKey = key == 40 ? PlayerInventoryUtils.OFFHAND_SLOT : key;
        final PlayerInventory playerInventory = player.getInventory();
        final boolean isInWindow = isClickInWindow(slot);
        final int clickSlot = isInWindow ? slot : slot - offset;
        final ItemStack clicked = isInWindow ? getItemStack(slot) : playerInventory.getItemStack(clickSlot);
        final ItemStack heldItem = playerInventory.getItemStack(convertedKey);
        final AbstractInventory clickedInventory = isInWindow ? this : playerInventory;
        final InventoryClickResult clickResult = clickProcessor.changeHeld(clicked, heldItem);
        if (clickResult.isCancel()) {
            updateAll(player);
            return false;
        }
        if (isInWindow) {
            setItemStack(slot, clickResult.getClicked());
        } else {
            playerInventory.setItemStack(clickSlot, clickResult.getClicked());
        }
        playerInventory.setItemStack(convertedKey, clickResult.getCursor());
        callClickEvent(player, clickedInventory, clickSlot, ClickType.CHANGE_HELD, clicked, playerInventory.getCursorItem());
        return true;
    }

    @Override
    public boolean middleClick(Player player, int slot) {
        // TODO
        update(player);
        return false;
    }

    @Override
    public boolean drop(Player player, boolean all, int slot) {
        final PlayerInventory playerInventory = player.getInventory();
        final boolean isInWindow = isClickInWindow(slot);
        final boolean outsideDrop = slot == -999;
        final int clickSlot = isInWindow ? slot : slot - offset;
        final ItemStack clicked = outsideDrop ?
                ItemStack.AIR : (isInWindow ? getItemStack(slot) : playerInventory.getItemStack(clickSlot));
        final ItemStack cursor = playerInventory.getCursorItem();
        final InventoryClickResult clickResult = clickProcessor.drop(player, all, clickSlot, clicked, cursor);
        if (clickResult.isCancel()) {
            updateAll(player);
            return false;
        }
        final ItemStack resultClicked = clickResult.getClicked();
        if (!outsideDrop && resultClicked != null) {
            if (isInWindow) {
                setItemStack(slot, resultClicked);
            } else {
                playerInventory.setItemStack(clickSlot, resultClicked);
            }
        }
        playerInventory.setCursorItem(clickResult.getCursor());
        return true;
    }

    @Override
    public boolean dragging(Player player, List<Integer> slots, int button) {
        final PlayerInventory playerInventory = player.getInventory();
        final ItemStack cursor = playerInventory.getCursorItem();

        final ItemStack clickResult = clickProcessor.dragging(player, this, slots, button, cursor);
        if (clickResult == null) {
            updateAll(player);
            return false;
        }
        playerInventory.setCursorItem(clickResult);
        updateAll(player); // FIXME: currently not properly client-predicted
        return true;
    }

    @Override
    public boolean doubleClick(Player player, int slot) {
        final PlayerInventory playerInventory = player.getInventory();
        final boolean isInWindow = isClickInWindow(slot);
        final int clickSlot = isInWindow ? slot : slot - offset;
        final ItemStack clicked = slot != -999 ?
                (isInWindow ? getItemStack(slot) : playerInventory.getItemStack(clickSlot)) :
                ItemStack.AIR;
        final ItemStack cursor = playerInventory.getCursorItem();
        final InventoryClickResult clickResult = clickProcessor.doubleClick(isInWindow ? this : playerInventory,
                this, player, clickSlot, clicked, cursor);
        if (clickResult.isCancel()) {
            updateAll(player);
            return false;
        }
        playerInventory.setCursorItem(clickResult.getCursor());
        updateAll(player); // FIXME: currently not properly client-predicted
        return true;
    }

    private boolean isClickInWindow(int slot) {
        return slot < getSize();
    }

    private void updateAll(Player player) {
        player.getInventory().update();
        update(player);
    }
}
