package io.github.bearisbug.synaxis.inventory.click;

import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.EquipmentSlot;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.inventory.InventoryClickEvent;
import io.github.bearisbug.synaxis.inventory.AbstractInventory;
import io.github.bearisbug.synaxis.inventory.PlayerInventory;
import io.github.bearisbug.synaxis.inventory.TransactionType;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.component.Equippable;
import io.github.bearisbug.synaxis.registry.RegistryTag;
import io.github.bearisbug.synaxis.utils.MathUtils;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

@ApiStatus.Internal
public final class InventoryClickProcessor {

    public InventoryClickResult leftClick(ItemStack clicked, ItemStack cursor) {
        if (cursor.isSimilar(clicked)) {
            // Try to stack items
            final int totalAmount = cursor.amount() + clicked.amount();
            final int maxSize = cursor.maxStackSize();
            if (!MathUtils.isBetween(totalAmount, 0, clicked.maxStackSize())) {
                // Size is too big, stack as much as possible into clicked
                cursor = cursor.withAmount(totalAmount - maxSize);
                clicked = clicked.withAmount(maxSize);
            } else {
                // Merge cursor item clicked
                cursor = ItemStack.AIR;
                clicked = clicked.withAmount(totalAmount);
            }
        } else {
            // Items are not compatible, swap them
            var temp = clicked;

            clicked = cursor;
            cursor = temp;
        }
        return new InventoryClickResult(clicked, cursor);
    }

    public InventoryClickResult rightClick(ItemStack clicked, ItemStack cursor) {
        final var result = new InventoryClickResult(clicked, cursor);

        if (clicked.isSimilar(cursor)) {
            // Items can be stacked
            final int amount = clicked.amount() + 1;
            if (!MathUtils.isBetween(amount, 0, clicked.maxStackSize())) {
                // Size too large, stop here
                return result;
            } else {
                // Add 1 to clicked
                result.setCursor(cursor.withAmount(operand -> operand - 1));
                result.setClicked(clicked.withAmount(amount));
            }
        } else {
            // Items cannot be stacked
            if (cursor.isAir()) {
                // Take half of clicked, rounded up without overflowing on the largest stack
                final int amount = clicked.amount() - clicked.amount() / 2;
                result.setCursor(clicked.withAmount(amount));
                result.setClicked(clicked.withAmount(operand -> operand / 2));
            } else {
                if (clicked.isAir()) {
                    // Put 1 to clicked
                    result.setCursor(cursor.withAmount(operand -> operand - 1));
                    result.setClicked(cursor.withAmount(1));
                } else {
                    // Swap items
                    result.setCursor(clicked);
                    result.setClicked(cursor);
                }
            }
        }
        return result;
    }

    public InventoryClickResult changeHeld(ItemStack clicked, ItemStack cursor) {
        return new InventoryClickResult(/* clicked= */ cursor, /* cursor= */ clicked); // Swap items
    }

    public InventoryClickResult shiftClick(AbstractInventory inventory, AbstractInventory targetInventory,
                                                    int start, int end, int step,
                                                    Player player, int slot,
                                                    ItemStack clicked, ItemStack cursor) {
        final InventoryClickResult clickResult = new InventoryClickResult(clicked, cursor);
        if (clicked.isAir()) return clickResult.cancelled();

        final boolean craftingGridClick = slot >= 36 && slot <= 40;

        // Handle armor and off-hand equippables
        if (inventory instanceof PlayerInventory && targetInventory instanceof PlayerInventory) {
            Equippable equippableComponent = clicked.get(DataComponents.EQUIPPABLE);
            if (equippableComponent != null) {
                final EquipmentSlot equipmentSlot = equippableComponent.slot();
                RegistryTag<EntityType> allowed = equippableComponent.allowedEntities();
                if ((allowed == null || allowed.contains(EntityType.PLAYER.registryKey()))
                        && (equipmentSlot.isArmor() || equipmentSlot == EquipmentSlot.OFF_HAND)
                        && !craftingGridClick) {
                    // Shift-click equip
                    final ItemStack currentItem = player.getEquipment(equipmentSlot);
                    if (currentItem.isAir()) {
                        player.setEquipment(equipmentSlot, clicked);
                        return new InventoryClickResult(ItemStack.AIR, cursor);
                    }
                }
            }
        }

        clickResult.setCancel(true);
        final var pair = TransactionType.ADD.process(targetInventory, clicked, (index, _) -> {
            if (inventory == targetInventory && index == slot) {
                return false; // Prevent item lose/duplication
            }

            clickResult.setCancel(false);
            return true;
        }, start, end, step);

        final ItemStack itemResult = pair.left();
        final Map<Integer, ItemStack> itemChangesMap = pair.right();
        itemChangesMap.forEach((Integer s, ItemStack itemStack) -> {
            targetInventory.setItemStack(s, itemStack);
            callClickEvent(player, targetInventory, s, ClickType.SHIFT_CLICK, itemStack, cursor);
        });

        clickResult.setClicked(itemResult);
        return clickResult;
    }

    public @Nullable ItemStack dragging(Player player, AbstractInventory inventory,
                                        List<Integer> slots, int button, ItemStack cursor) {
        // Validate every slot before writing anything: a write that is interrupted halfway through leaves the
        // items in place while the cursor is never debited, which duplicates them.
        final List<SlotRef> refs = new ArrayList<>(slots.size());
        for (int slot : slots) {
            final boolean isInWindow = slot < inventory.getSize();
            final AbstractInventory inv = isInWindow ? inventory : player.getInventory();
            final int s = isInWindow ? slot : slot - inventory.getSize();
            if (s < 0 || s >= inv.getSize()) return null;
            refs.add(new SlotRef(inv, s));
        }
        // A drag over no slot, which a client can send, moves nothing
        if (refs.isEmpty()) return cursor;

        // Drag instruction
        if (button == 2) return dragEvenly(player, refs, cursor); // End left
        if (button == 6) return dragOneEach(player, refs, cursor); // End right
        return cursor;
    }

    /** A slot of a drag: the inventory it is in and its index there. */
    private record SlotRef(AbstractInventory inventory, int slot) {
    }

    /** Splits the cursor evenly over the slots; null if there are more slots than items. */
    private static @Nullable ItemStack dragEvenly(Player player, List<SlotRef> slots, ItemStack cursor) {
        final int slotCount = slots.size();
        final int cursorAmount = cursor.amount();
        if (slotCount > cursorAmount) return null;

        // Should be size of each defined slot (if not full)
        final int slotSize = cursorAmount / slotCount;
        // Place all waiting drag action
        int finalCursorAmount = cursorAmount;
        for (SlotRef ref : slots) {
            ItemStack slotItem = ref.inventory().getItemStack(ref.slot());
            final int amount = slotItem.amount();
            if (cursor.isSimilar(slotItem)) {
                if (MathUtils.isBetween(amount + slotSize, 0, slotItem.maxStackSize())) {
                    // Append divided amount to slot
                    slotItem = slotItem.withAmount(a -> a + slotSize);
                    finalCursorAmount -= slotSize;
                } else {
                    // Amount too big, fill as much as possible
                    final int maxSize = cursor.maxStackSize();
                    final int removedAmount = maxSize - amount;
                    slotItem = slotItem.withAmount(maxSize);
                    finalCursorAmount -= removedAmount;
                }
            } else if (slotItem.isAir()) {
                // Slot is empty, add divided amount
                slotItem = cursor.withAmount(slotSize);
                finalCursorAmount -= slotSize;
            }
            ref.inventory().setItemStack(ref.slot(), slotItem);
            callClickEvent(player, ref.inventory(), ref.slot(), ClickType.LEFT_DRAGGING, slotItem, cursor);
        }
        // Update the cursor
        return cursor.withAmount(finalCursorAmount);
    }

    /** Places one item of the cursor in each slot; null if there are more slots than items. */
    private static @Nullable ItemStack dragOneEach(Player player, List<SlotRef> slots, ItemStack cursor) {
        final int cursorAmount = cursor.amount();
        if (slots.size() > cursorAmount) return null;
        // Place all waiting drag action
        int finalCursorAmount = cursorAmount;
        for (SlotRef ref : slots) {
            ItemStack slotItem = ref.inventory().getItemStack(ref.slot());
            if (cursor.isSimilar(slotItem)) {
                // Compatible item in the slot, increment by 1
                final int amount = slotItem.amount() + 1;
                if (MathUtils.isBetween(amount, 0, slotItem.maxStackSize())) {
                    slotItem = slotItem.withAmount(amount);
                    finalCursorAmount -= 1;
                }
            } else if (slotItem.isAir()) {
                // No item at the slot, place one
                slotItem = cursor.withAmount(1);
                finalCursorAmount -= 1;
            }
            ref.inventory().setItemStack(ref.slot(), slotItem);
            callClickEvent(player, ref.inventory(), ref.slot(), ClickType.RIGHT_DRAGGING, slotItem, cursor);
        }
        // Update the cursor
        return cursor.withAmount(finalCursorAmount);
    }

    public InventoryClickResult doubleClick(AbstractInventory clickedInventory, AbstractInventory inventory, Player player, int slot,
                                                     ItemStack clicked, ItemStack cursor) {
        InventoryClickResult clickResult = new InventoryClickResult(clicked, cursor);
        if (cursor.isAir()) return clickResult.cancelled();

        final int amount = cursor.amount();
        final int maxSize = cursor.maxStackSize();
        final int remainingAmount = maxSize - amount;
        if (remainingAmount <= 0) {
            // Item is already full, or holds more than a stack: there is nothing to collect
            return clickResult;
        }
        final BiFunction<AbstractInventory, ItemStack, ItemStack> func = (inv, rest) -> {
            // Vanilla scans twice: the first pass may only draw from stacks that are not already full,
            // so scattered items are collected before an untouched stack is broken up.
            rest = take(player, inv, clickedInventory, slot, cursor, rest, true);
            if (rest.isAir()) return rest;
            return take(player, inv, clickedInventory, slot, cursor, rest, false);
        };

        // The clicked inventory first, then the other one if a container is open: the player's inventory when the
        // container was clicked, the container when the player's inventory was
        final AbstractInventory other = clickedInventory == inventory ? player.getInventory() : inventory;
        ItemStack remain = func.apply(clickedInventory, cursor.withAmount(remainingAmount));
        if (!remain.isAir() && other != clickedInventory) remain = func.apply(other, remain);

        // Air has an amount of 0
        clickResult.setCursor(cursor.withAmount(amount + remainingAmount - remain.amount()));
        return clickResult;
    }

    private static ItemStack take(Player player, AbstractInventory inventory, AbstractInventory clickedInventory,
                                  int slot, ItemStack cursor, ItemStack rest, boolean partialStacksOnly) {
        var pair = TransactionType.TAKE.process(inventory, rest, (index, itemStack) -> {
            // Prevent item loss/duplication
            if (index == slot && clickedInventory == inventory) return false;
            return !partialStacksOnly || itemStack.amount() < itemStack.maxStackSize();
        });
        pair.right().forEach((Integer s, ItemStack itemStack) -> {
            inventory.setItemStack(s, itemStack);
            callClickEvent(player, inventory, s, ClickType.DOUBLE_CLICK, itemStack, cursor);
        });
        return pair.left();
    }

    public InventoryClickResult drop(Player player,
                                              boolean all, int slot, ItemStack clicked, ItemStack cursor) {
        final InventoryClickResult clickResult = new InventoryClickResult(clicked, cursor);
        // Slot -999 is a click outside the window, which drops from the cursor; the drop key drops from the slot.
        // All drops the stack (left click, Ctrl + Q), otherwise a single item (right click, Q).
        final boolean fromCursor = slot == -999;
        final ItemStack source = fromCursor ? cursor : clicked;
        if (!player.dropItem(all ? source : source.withAmount(1))) return clickResult.cancelled();
        final ItemStack rest = all ? ItemStack.AIR : source.withAmount(source.amount() - 1);
        if (fromCursor) clickResult.setCursor(rest);
        else clickResult.setClicked(rest);
        return clickResult;
    }

    private static void callClickEvent(Player player, AbstractInventory inventory, int slot,
                                ClickType clickType, ItemStack clicked, ItemStack cursor) {
        AbstractInventory.dispatch(player.process(), new InventoryClickEvent(inventory, player, slot, clickType, clicked, cursor));
    }
}