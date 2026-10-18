package io.github.bearisbug.synaxis.inventory;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.EquipmentSlot;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.item.EntityEquipEvent;
import io.github.bearisbug.synaxis.inventory.click.ClickType;
import io.github.bearisbug.synaxis.inventory.click.InventoryClickResult;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.network.packet.server.play.SetCursorItemPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetPlayerInventorySlotPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetSlotPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.WindowItemsPacket;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.BOOTS_SLOT;
import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.CHESTPLATE_SLOT;
import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.HELMET_SLOT;
import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.LEGGINGS_SLOT;
import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.OFFHAND_SLOT;
import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.convertEngineSlotToPlayerInventorySlot;
import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.convertEngineSlotToWindowSlot;
import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.isPlayerInventorySlot;

/**
 * Represents the inventory of a {@link Player}, retrieved with {@link Player#getInventory()}.
 */
public non-sealed class PlayerInventory extends AbstractInventory {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerInventory.class);
    // A listener that swaps the item would warn on every click, so only the first refusal is logged
    private static final AtomicBoolean REFUSED_EQUIP_LOGGED = new AtomicBoolean();

    public static final int INVENTORY_SIZE = 46;
    public static final int INNER_INVENTORY_SIZE = 36;

    private ItemStack cursorItem = ItemStack.AIR;

    public PlayerInventory(ServerProcess process) {
        super(process, INVENTORY_SIZE);
    }

    @Override
    public synchronized void clear() {
        cursorItem = ItemStack.AIR;
        super.clear();
    }

    @Override
    public int getInnerSize() {
        return INNER_INVENTORY_SIZE;
    }

    @Override
    public byte getWindowId() {
        return 0;
    }

    private static int getSlotId(EquipmentSlot slot, byte heldSlot) {
        return switch (slot) {
            case MAIN_HAND -> heldSlot;
            case OFF_HAND -> OFFHAND_SLOT;
            default -> slot.armorSlot();
        };
    }

    private static @Nullable EquipmentSlot getEquipmentSlot(int slot, byte heldSlot) {
        return switch (slot) {
            case OFFHAND_SLOT -> EquipmentSlot.OFF_HAND;
            case HELMET_SLOT -> EquipmentSlot.HELMET;
            case CHESTPLATE_SLOT -> EquipmentSlot.CHESTPLATE;
            case LEGGINGS_SLOT -> EquipmentSlot.LEGGINGS;
            case BOOTS_SLOT -> EquipmentSlot.BOOTS;
            default -> slot == heldSlot ? EquipmentSlot.MAIN_HAND : null;
        };
    }

    public ItemStack getEquipment(EquipmentSlot slot, byte heldSlot) {
        final int slotId = getSlotId(slot, heldSlot);
        if (slotId < 0) return ItemStack.AIR;
        return getItemStack(slotId);
    }

    public void setEquipment(EquipmentSlot slot, byte heldSlot, ItemStack itemStack) {
        final int slotId = getSlotId(slot, heldSlot);
        if (slotId < 0) Check.fail("PlayerInventory does not support {0} equipment", slot);

        setItemStack(slotId, itemStack);
    }

    @Override
    public void update(Player player) {
        player.sendPacket(createWindowItemsPacket());
    }

    /**
     * Gets the item in player cursor.
     *
     * @return the cursor item
     */
    public synchronized ItemStack getCursorItem() {
        return cursorItem;
    }

    /**
     * Changes the player cursor item.
     *
     * @param cursorItem the new cursor item
     */
    public void setCursorItem(ItemStack cursorItem) {
        setCursorItem(cursorItem, true);
    }

    /**
     * Changes the player cursor item.
     *
     * @param cursorItem the new cursor item
     * @param sendPacket true to send the update packet to the client, false otherwise
     */
    public synchronized void setCursorItem(ItemStack cursorItem, boolean sendPacket) {
        if (this.cursorItem.equals(cursorItem)) return;
        this.cursorItem = cursorItem;
        if (sendPacket) sendPacketToViewers(new SetCursorItemPacket(cursorItem));
    }

    @Override
    protected void UNSAFE_itemInsert(int slot, ItemStack item, ItemStack previous, boolean sendPacket) {
        for (Player player : getViewers()) {
            final EquipmentSlot equipmentSlot = getEquipmentSlot(slot, player.getHeldSlot());
            if (equipmentSlot == null) continue;

            EntityEquipEvent entityEquipEvent = new EntityEquipEvent(player, item, equipmentSlot);
            player.process().eventHandler().call(entityEquipEvent);
            item = acceptedEquipment(slot, item, entityEquipEvent.getEquippedItem());

            player.updateEquipmentAttributes(previous, item, equipmentSlot);
            player.syncEquipment(equipmentSlot, item);
        }

        super.UNSAFE_itemInsert(slot, item, previous, sendPacket);
    }

    /**
     * Keeps the item an {@link EntityEquipEvent} listener set when it differs from the requested one in its components
     * only. A click writes the cursor after the slot from what it computed beforehand, so another material or amount in
     * the slot would duplicate or destroy items.
     */
    private static ItemStack acceptedEquipment(int slot, ItemStack requested, ItemStack replacement) {
        if (replacement.material() == requested.material() && replacement.amount() == requested.amount())
            return replacement;
        if (REFUSED_EQUIP_LOGGED.compareAndSet(false, true)) {
            LOGGER.warn("EntityEquipEvent on a player inventory may only change item components: slot {} keeps {} x{}"
                            + " instead of {} x{}; cancel InventoryPreClickEvent or PlayerSwapItemEvent to block equipping"
                            + " (logged once)", slot, requested.material().name(), requested.amount(),
                    replacement.material().name(), replacement.amount());
        }
        return requested;
    }

    @Override
    public void sendSlotRefresh(int slot, ItemStack item) {
        if (slot < 0 || slot > INVENTORY_SIZE)
            return; // Sanity check
        // See note in PlayerInventoryUtils about why we do this conversion
        boolean isPlayerInventorySlot = isPlayerInventorySlot(slot);
        int packetSlot = isPlayerInventorySlot
                ? convertEngineSlotToPlayerInventorySlot(slot)
                : convertEngineSlotToWindowSlot(slot);

        sendPacketToViewers(isPlayerInventorySlot
                ? new SetPlayerInventorySlotPacket(packetSlot, item)
                : new SetSlotPacket(0, 0, (short) packetSlot, item));
    }

    /**
     * Gets a {@link WindowItemsPacket} with all the items in the inventory.
     *
     * @return a {@link WindowItemsPacket} with inventory items
     */
    private WindowItemsPacket createWindowItemsPacket() {
        ItemStack[] convertedSlots = new ItemStack[INVENTORY_SIZE];
        for (int i = 0; i < itemStacks.length; i++) {
            final int slot = convertEngineSlotToWindowSlot(i);
            convertedSlots[slot] = itemStacks[i];
        }
        return new WindowItemsPacket(0, 0, List.of(convertedSlots), getCursorItem());
    }

    @Override
    public boolean leftClick(Player player, int slot) {
        final ItemStack cursor = getCursorItem();
        final ItemStack clicked = getItemStack(slot);
        final InventoryClickResult clickResult = clickProcessor.leftClick(clicked, cursor);
        if (clickResult.isCancel()) {
            update();
            return false;
        }
        setItemStack(slot, clickResult.getClicked());
        setCursorItem(clickResult.getCursor());
        callClickEvent(player, this, slot, ClickType.LEFT_CLICK, clicked, cursor);
        return true;
    }

    @Override
    public boolean rightClick(Player player, int slot) {
        final ItemStack cursor = getCursorItem();
        final ItemStack clicked = getItemStack(slot);
        final InventoryClickResult clickResult = clickProcessor.rightClick(clicked, cursor);
        if (clickResult.isCancel()) {
            update();
            return false;
        }
        setItemStack(slot, clickResult.getClicked());
        setCursorItem(clickResult.getCursor());
        callClickEvent(player, this, slot, ClickType.RIGHT_CLICK, clicked, cursor);
        return true;
    }

    @Override
    public boolean middleClick(Player player, int slot) {
        // TODO
        update();
        return false;
    }

    @Override
    public boolean drop(Player player, boolean all, int slot) {
        final ItemStack cursor = getCursorItem();
        final boolean outsideDrop = slot == -999;
        final ItemStack clicked = outsideDrop ? ItemStack.AIR : getItemStack(slot);
        final InventoryClickResult clickResult = clickProcessor.drop(player, all, slot, clicked, cursor);
        if (clickResult.isCancel()) {
            update();
            return false;
        }
        final ItemStack resultClicked = clickResult.getClicked();
        if (resultClicked != null && !outsideDrop) {
            setItemStack(slot, resultClicked);
        }
        setCursorItem(clickResult.getCursor());
        return true;
    }

    @Override
    public boolean shiftClick(Player player, int slot, int button) {
        final ItemStack cursor = getCursorItem();
        final ItemStack clicked = getItemStack(slot);
        final boolean craftingGridClick = slot > 36 && slot <= 40;
        final boolean craftingResultClick = slot == 36;
        final boolean hotBarClick = slot < 9;

        // the client has different behavior for clicking based on where the item is in the inventory
        InventoryClickResult clickResult;
        final EquipmentSlot equipmentSlot = getEquipmentSlot(slot, player.getHeldSlot());
        if (equipmentSlot != null && (equipmentSlot.isArmor() || equipmentSlot == EquipmentSlot.OFF_HAND)) {
            // CASE: shift-clicking equipped armor or your off-hand item
            // we want to go through the inventory slots first
            // and then through the hotbar going left to right
            clickResult = shiftClickChained(player, slot, clicked, cursor,
                    9, INNER_INVENTORY_SIZE, 1,
                    0, 9, 1);
        } else if (craftingGridClick) {
            // CASE: shift-clicking an item from the crafting grid into your inventory
            // we want to prioritize the inventory from left-to-right and then the hotbar from left-to-right
            clickResult = shiftClickChained(player, slot, clicked, cursor,
                    9, INNER_INVENTORY_SIZE, 1,
                    0, 9, 1);
        } else if (craftingResultClick) {
            // CASE: shift-clicking an item from the crafting grid result into your inventory
            // we want to prioritize the hotbar from right-to-left and then the inventory from right-to-left
            clickResult = shiftClickChained(player, slot, clicked, cursor,
                    8, -1, -1,
                    INNER_INVENTORY_SIZE - 1, 8, -1);
        } else {
            // CASE: shift-clicking an item in the hotbar or inventory
            clickResult = clickProcessor.shiftClick(
                    this, this,
                    (hotBarClick ? 9 : 0), (hotBarClick ? INNER_INVENTORY_SIZE : 9), 1,
                    player, slot, clicked, cursor
            );
        }

        if (clickResult.isCancel()) {
            update();
            return false;
        }

        setItemStack(slot, clickResult.getClicked());
        setCursorItem(clickResult.getCursor());
        update(); // FIXME: currently not properly client-predicted
        return true;
    }

    /**
     * Runs the two shift-click passes in sequence: whatever the first range could not take is offered to the second
     * one instead of being written back into the clicked slot.
     */
    private InventoryClickResult shiftClickChained(Player player, int slot, ItemStack clicked, ItemStack cursor,
                                                   int firstStart, int firstEnd, int firstStep,
                                                   int secondStart, int secondEnd, int secondStep) {
        final InventoryClickResult first = clickProcessor.shiftClick(this, this,
                firstStart, firstEnd, firstStep, player, slot, clicked, cursor);
        if (first.getClicked().isAir()) return first;

        final InventoryClickResult second = clickProcessor.shiftClick(this, this,
                secondStart, secondEnd, secondStep, player, slot, first.getClicked(), cursor);
        return second.isCancel() ? first : second;
    }

    @Override
    public boolean changeHeld(Player player, int slot, int key) {
        final ItemStack cursorItem = getCursorItem();
        if (!cursorItem.isAir()) return false;
        final ItemStack heldItem = getItemStack(key);
        final ItemStack clicked = getItemStack(slot);
        final InventoryClickResult clickResult = clickProcessor.changeHeld(clicked, heldItem);
        if (clickResult.isCancel()) {
            update();
            return false;
        }
        setItemStack(slot, clickResult.getClicked());
        setItemStack(key, clickResult.getCursor());
        callClickEvent(player, this, slot, ClickType.CHANGE_HELD, clicked, cursorItem);
        return true;
    }

    @Override
    public boolean dragging(Player player, List<Integer> slots, int button) {
        final ItemStack cursor = getCursorItem();

        final ItemStack clickResult = clickProcessor.dragging(player, this, slots, button, cursor);
        if (clickResult == null) {
            update();
            return false;
        }
        setCursorItem(clickResult);
        update(); // FIXME: currently not properly client-predicted
        return true;
    }

    @Override
    public boolean doubleClick(Player player, int slot) {
        final ItemStack cursor = getCursorItem();
        final ItemStack clicked = getItemStack(slot);
        final InventoryClickResult clickResult = clickProcessor.doubleClick(this, this, player, slot, clicked, cursor);
        if (clickResult.isCancel()) {
            update();
            return false;
        }
        setCursorItem(clickResult.getCursor());
        update(); // FIXME: currently not properly client-predicted
        return true;
    }

}
