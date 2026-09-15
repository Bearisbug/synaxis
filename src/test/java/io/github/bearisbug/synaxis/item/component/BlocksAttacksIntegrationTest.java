package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerActionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientUseItemPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class BlocksAttacksIntegrationTest {

    @Test
    public void test(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));

        player.setItemInMainHand(ItemStack.of(Material.SHIELD));

        player.addPacketToQueue(new ClientUseItemPacket(PlayerHand.MAIN, 0, 0f, 0f));
        player.interpretPacketQueue();

        assertTrue(player.isUsingItem());
        assertTrue(player.getPlayerMeta().isHandActive());

        player.addPacketToQueue(new ClientPlayerActionPacket(ClientPlayerActionPacket.Status.UPDATE_ITEM_STATE, player.getPosition(), BlockFace.NORTH, 1));
        player.interpretPacketQueue();

        assertFalse(player.isUsingItem());
        assertFalse(player.getPlayerMeta().isHandActive());

        player.setItemInMainHand(ItemStack.of(Material.DIAMOND_SWORD).with(DataComponents.BLOCKS_ATTACKS,
                new BlocksAttacks(1f, 1f, List.of(), BlocksAttacks.ItemDamageFunction.DEFAULT, null, null, null)));

        player.addPacketToQueue(new ClientUseItemPacket(PlayerHand.MAIN, 0, 0f, 0f));
        player.interpretPacketQueue();

        assertTrue(player.isUsingItem());
        assertTrue(player.getPlayerMeta().isHandActive());
    }
}
