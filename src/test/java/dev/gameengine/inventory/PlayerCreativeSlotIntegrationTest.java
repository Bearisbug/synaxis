package dev.gameengine.inventory;

import dev.gameengine.coordinate.Pos;
import dev.gameengine.entity.GameMode;
import dev.gameengine.item.ItemStack;
import dev.gameengine.item.Material;
import dev.gameengine.listener.CreativeInventoryActionListener;
import dev.gameengine.network.packet.client.play.ClientCreativeInventoryActionPacket;
import dev.gameengine.utils.inventory.PlayerInventoryUtils;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class PlayerCreativeSlotIntegrationTest {

    @Test
    public void testCreativeSlots(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        assertEquals(instance, player.getInstance());

        player.setGameMode(GameMode.CREATIVE);
        player.addPacketToQueue(new ClientCreativeInventoryActionPacket((short) PlayerInventoryUtils.OFFHAND_SLOT, ItemStack.of(Material.STICK)));
        player.interpretPacketQueue();
        assertEquals(Material.STICK, player.getItemInOffHand().material());
    }

    @Test
    public void testBoundsCheck(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        player.setGameMode(GameMode.CREATIVE);

        assertDoesNotThrow(() -> CreativeInventoryActionListener.listener(new ClientCreativeInventoryActionPacket((short) 76, ItemStack.of(Material.OAK_LOG)), player));
    }
}
