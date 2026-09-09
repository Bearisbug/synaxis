package dev.gameengine.inventory.click;

import dev.gameengine.inventory.Inventory;
import dev.gameengine.inventory.InventoryType;
import dev.gameengine.item.ItemStack;
import dev.gameengine.network.packet.client.play.ClientClickWindowPacket;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class ClickUtils {
    public static final InventoryType TYPE = InventoryType.HOPPER;

    public static final int SIZE = TYPE.getSize(); // Default hopper size

    public static Inventory createInventory() {
        return new Inventory(TYPE, "TestInventory");
    }

    public static void assertProcessed(ClickPreprocessor preprocessor, @Nullable Click info, ClientClickWindowPacket packet) {
        assertEquals(info, preprocessor.processClick(packet, SIZE));
    }

    public static void assertProcessed(@Nullable Click info, ClientClickWindowPacket packet) {
        assertProcessed(new ClickPreprocessor(), info, packet);
    }

    public static ClientClickWindowPacket clickPacket(ClientClickWindowPacket.ClickType type, int windowId, int button, int slot) {
        return new ClientClickWindowPacket((byte) windowId, 0, (short) slot, (byte) button, type, Map.of(), ItemStack.Hash.AIR);
    }
}