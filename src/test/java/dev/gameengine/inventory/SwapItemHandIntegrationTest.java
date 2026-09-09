package dev.gameengine.inventory;

import dev.gameengine.coordinate.Pos;
import dev.gameengine.coordinate.Vec;
import dev.gameengine.event.player.PlayerSwapItemEvent;
import dev.gameengine.coordinate.BlockFace;
import dev.gameengine.item.ItemStack;
import dev.gameengine.item.Material;
import dev.gameengine.network.packet.client.play.ClientPlayerActionPacket;
import dev.gameengine.network.packet.server.play.WindowItemsPacket;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class SwapItemHandIntegrationTest {

    /**
     * The client predicts the swap locally, so cancelling PlayerSwapItemEvent has to resynchronise it or the two
     * sides disagree about where the item is.
     */
    @Test
    public void cancelledSwapResynchronisesTheClient(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));

        player.getInventory().setItemStack(player.getHeldSlot(), ItemStack.of(Material.DIAMOND));
        env.process().eventHandler().addListener(PlayerSwapItemEvent.class, event -> event.setCancelled(true));

        var tracker = connection.trackIncoming(WindowItemsPacket.class);
        player.addPacketToQueue(new ClientPlayerActionPacket(
                ClientPlayerActionPacket.Status.SWAP_ITEM_HAND, Vec.ZERO, BlockFace.BOTTOM, 0));
        player.interpretPacketQueue();

        assertEquals(ItemStack.of(Material.DIAMOND), player.getItemInMainHand());
        assertEquals(ItemStack.AIR, player.getItemInOffHand());
        tracker.assertCount(1);
    }
}
