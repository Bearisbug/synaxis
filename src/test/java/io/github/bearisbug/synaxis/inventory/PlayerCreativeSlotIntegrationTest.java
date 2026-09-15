package io.github.bearisbug.synaxis.inventory;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.listener.CreativeInventoryActionListener;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientCreativeInventoryActionPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
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
