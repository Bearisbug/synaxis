package io.github.bearisbug.synaxis.inventory;

import org.junit.jupiter.api.Test;

import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.BOOTS_SLOT;
import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.CHESTPLATE_SLOT;
import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.CRAFT_RESULT;
import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.CRAFT_SLOT_1;
import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.CRAFT_SLOT_2;
import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.CRAFT_SLOT_3;
import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.CRAFT_SLOT_4;
import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.HELMET_SLOT;
import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.LEGGINGS_SLOT;
import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.OFFHAND_SLOT;
import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.convertWindow0SlotToEngineSlot;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test conversion from packet slots to internal ones (used in events and inventory methods)
 */
public class PlayerSlotConversionTest {

    @Test
    public void hotbar() {
        // Convert 36-44 into 0-8
        for (int i = 0; i < 9; i++) {
            assertEquals(i, convertWindow0SlotToEngineSlot(i + 36));
        }
    }

    @Test
    public void mainInventory() {
        // No conversion, slots should stay 9-35
        for (int i = 9; i < 9 * 4; i++) {
            assertEquals(i, convertWindow0SlotToEngineSlot(i));
        }
    }

    @Test
    public void armor() {
        assertEquals(HELMET_SLOT, 41);
        assertEquals(CHESTPLATE_SLOT, 42);
        assertEquals(LEGGINGS_SLOT, 43);
        assertEquals(BOOTS_SLOT, 44);
        assertEquals(OFFHAND_SLOT, 45);

        // Convert 5-8 & 45 into 41-45
        assertEquals(HELMET_SLOT, convertWindow0SlotToEngineSlot(5));
        assertEquals(CHESTPLATE_SLOT, convertWindow0SlotToEngineSlot(6));
        assertEquals(LEGGINGS_SLOT, convertWindow0SlotToEngineSlot(7));
        assertEquals(BOOTS_SLOT, convertWindow0SlotToEngineSlot(8));
        assertEquals(OFFHAND_SLOT, convertWindow0SlotToEngineSlot(45));
    }

    @Test
    public void craft() {
        assertEquals(CRAFT_RESULT, 36);
        assertEquals(CRAFT_SLOT_1, 37);
        assertEquals(CRAFT_SLOT_2, 38);
        assertEquals(CRAFT_SLOT_3, 39);
        assertEquals(CRAFT_SLOT_4, 40);

        // Convert 0-4 into 36-40
        assertEquals(CRAFT_RESULT, convertWindow0SlotToEngineSlot(0));
        assertEquals(CRAFT_SLOT_1, convertWindow0SlotToEngineSlot(1));
        assertEquals(CRAFT_SLOT_2, convertWindow0SlotToEngineSlot(2));
        assertEquals(CRAFT_SLOT_3, convertWindow0SlotToEngineSlot(3));
        assertEquals(CRAFT_SLOT_4, convertWindow0SlotToEngineSlot(4));
    }
}
