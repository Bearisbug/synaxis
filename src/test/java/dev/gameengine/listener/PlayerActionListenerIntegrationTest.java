package dev.gameengine.listener;

import dev.gameengine.coordinate.Pos;
import dev.gameengine.coordinate.Vec;
import dev.gameengine.event.EventFilter;
import dev.gameengine.event.player.PlayerStabEvent;
import dev.gameengine.instance.block.BlockFace;
import dev.gameengine.item.ItemStack;
import dev.gameengine.item.Material;
import dev.gameengine.network.packet.client.play.ClientPlayerActionPacket;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

@EnvTest
public class PlayerActionListenerIntegrationTest {

    @Test
    public void testStabInvalidWeapon(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 0, 0));

        var tracker = env.trackEvent(PlayerStabEvent.class, EventFilter.PLAYER, player);

        PlayerActionListener.playerActionListener(new ClientPlayerActionPacket(
                ClientPlayerActionPacket.Status.STAB,
                Vec.ZERO, BlockFace.NORTH, 0
        ), player);
        tracker.assertEmpty();
    }

    @Test
    public void testStabWithWeapon(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 0, 0));
        player.setItemInMainHand(ItemStack.of(Material.NETHERITE_SPEAR));

        var tracker = env.trackEvent(PlayerStabEvent.class, EventFilter.PLAYER, player);
        PlayerActionListener.playerActionListener(new ClientPlayerActionPacket(
                ClientPlayerActionPacket.Status.STAB,
                Vec.ZERO, BlockFace.NORTH, 0
        ), player);

        tracker.assertSingle();
    }

}
