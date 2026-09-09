package dev.gameengine.inventory.type;

import net.kyori.adventure.text.Component;
import dev.gameengine.inventory.Inventory;
import dev.gameengine.inventory.InventoryProperty;
import dev.gameengine.inventory.InventoryType;

public class AnvilInventory extends Inventory {

    private short repairCost;

    public AnvilInventory(Component title) {
        super(InventoryType.ANVIL, title);
    }

    public AnvilInventory(String title) {
        super(InventoryType.ANVIL, title);
    }

    /**
     * Gets the anvil repair cost.
     *
     * @return the repair cost
     */
    public short getRepairCost() {
        return repairCost;
    }

    /**
     * Sets the anvil repair cost.
     *
     * @param cost the new anvil repair cost
     */
    public void setRepairCost(short cost) {
        this.repairCost = cost;
        sendProperty(InventoryProperty.ANVIL_REPAIR_COST, cost);
    }
}
