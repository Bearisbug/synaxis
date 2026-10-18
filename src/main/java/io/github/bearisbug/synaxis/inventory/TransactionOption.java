package io.github.bearisbug.synaxis.inventory;

import io.github.bearisbug.synaxis.item.ItemStack;

import java.util.Map;

@FunctionalInterface
public interface TransactionOption<T> {

    /**
     * Moves as much of the item as possible.
     * <p>
     * Returns what could not be moved, air when all of it was: with {@link TransactionType#ADD} the part that did not
     * fit, with {@link TransactionType#TAKE} the part that was not found (taking 64 diamonds out of 40 returns 24
     * diamonds and empties the inventory).
     */
    TransactionOption<ItemStack> ALL = (inventory, result, itemChangesMap) -> {
        itemChangesMap.forEach(inventory::setItemStack);
        return result;
    };

    /**
     * Moves the item only if all of it can be moved: added with {@link TransactionType#ADD}, found and taken with
     * {@link TransactionType#TAKE}.
     * <p>
     * Returns true if the item has been moved, false if nothing changed. This holds for a single item; a list is not
     * one transaction, see {@link AbstractInventory#processItemStacks(java.util.List, TransactionType, TransactionOption)}.
     */
    TransactionOption<Boolean> ALL_OR_NOTHING = (inventory, result, itemChangesMap) -> {
        if (result.isAir()) {
            // Item can be fully placed inside the inventory, do so
            itemChangesMap.forEach(inventory::setItemStack);
            return true;
        } else {
            // Inventory cannot accept the item fully
            return false;
        }
    };

    /**
     * Loop through the inventory items without changing anything.
     * <p>
     * Returns true if all of the item could be moved (added with {@link TransactionType#ADD}, taken with
     * {@link TransactionType#TAKE}), false otherwise. Each item of a list is checked against the unchanged inventory,
     * see {@link AbstractInventory#processItemStacks(java.util.List, TransactionType, TransactionOption)}.
     */
    TransactionOption<Boolean> DRY_RUN = (_, result, _) -> result.isAir();

    T fill(AbstractInventory inventory,
                    ItemStack result,
                    Map<Integer, ItemStack> itemChangesMap);

    default T fill(TransactionType type,
                            AbstractInventory inventory,
                            ItemStack itemStack) {
        var pair = type.process(inventory, itemStack);
        return fill(inventory, pair.left(), pair.right());
    }
}
