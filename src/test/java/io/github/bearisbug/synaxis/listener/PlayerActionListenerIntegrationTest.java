package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.player.PlayerStabEvent;
import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerActionPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    public void testStabKeepsTheItemItWasCalledWith(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 0, 0));
        var spear = ItemStack.of(Material.NETHERITE_SPEAR);
        player.setItemInMainHand(spear);

        var tracker = env.trackEvent(PlayerStabEvent.class, EventFilter.PLAYER, player);
        PlayerActionListener.playerActionListener(new ClientPlayerActionPacket(
                ClientPlayerActionPacket.Status.STAB,
                Vec.ZERO, BlockFace.NORTH, 0
        ), player);
        player.setItemInMainHand(ItemStack.of(Material.DIAMOND));

        tracker.assertSingle(event -> assertEquals(spear, event.getItemStack()));
    }

}
