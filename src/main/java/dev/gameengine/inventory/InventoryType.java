package dev.gameengine.inventory;

/**
 * Represents a type of {@link Inventory}
 */
public enum InventoryType {

    CHEST_1_ROW(9),
    CHEST_2_ROW(18),
    CHEST_3_ROW(27),
    CHEST_4_ROW(36),
    CHEST_5_ROW(45),
    CHEST_6_ROW(54),
    WINDOW_3X3(9),
    CRAFTER_3X3(9),
    ANVIL(3, 0, 2),
    BEACON(1),
    BLAST_FURNACE(3, 0, 2),
    BREWING_STAND(5),
    CRAFTING(10, 1, 10),
    ENCHANTMENT(2),
    FURNACE(3, 0, 2),
    GRINDSTONE(3, 0, 2),
    HOPPER(5),
    LECTERN(1),
    LOOM(4, 0, 3),
    MERCHANT(3, 0, 2),
    SHULKER_BOX(27),
    SMITHING(4, 0, 3),
    SMOKER(3, 0, 2),
    CARTOGRAPHY(3, 0, 2),
    STONE_CUTTER(2, 0, 1);

    private final int size;
    private final int inputSlotStart;
    private final int inputSlotEnd;

    InventoryType(int size) {
        this(size, 0, size);
    }

    InventoryType(int size, int inputSlotStart, int inputSlotEnd) {
        this.size = size;
        this.inputSlotStart = inputSlotStart;
        this.inputSlotEnd = inputSlotEnd;
    }

    public int getWindowType() {
        return ordinal();
    }

    public int getSize() {
        return size;
    }

    /**
     * Gets the first slot that accepts an item moved into this inventory, for example by a shift-click.
     *
     * @return the first input slot
     */
    public int getInputSlotStart() {
        return inputSlotStart;
    }

    /**
     * Gets the slot after the last one that accepts an item moved into this inventory. Result and output slots are
     * excluded, since the client never predicts an item landing in them.
     *
     * @return the exclusive end of the input slot range
     */
    public int getInputSlotEnd() {
        return inputSlotEnd;
    }

    /**
     * @deprecated use {@link #getSize()}
     */
    @Deprecated
    public int getAdditionalSlot() {
        return size;
    }

}
