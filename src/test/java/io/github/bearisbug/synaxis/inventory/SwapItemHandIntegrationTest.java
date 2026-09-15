package io.github.bearisbug.synaxis.inventory;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.event.player.PlayerSwapItemEvent;
import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerActionPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.WindowItemsPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
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
