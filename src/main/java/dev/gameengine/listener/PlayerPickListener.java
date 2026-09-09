package dev.gameengine.listener;

import dev.gameengine.entity.Entity;
import dev.gameengine.entity.Player;
import dev.gameengine.event.player.PlayerPickBlockEvent;
import dev.gameengine.event.player.PlayerPickEntityEvent;
import dev.gameengine.instance.Instance;
import dev.gameengine.instance.block.Block;
import dev.gameengine.network.packet.client.play.ClientPickItemFromBlockPacket;
import dev.gameengine.network.packet.client.play.ClientPickItemFromEntityPacket;

public class PlayerPickListener {

    public static void playerPickBlockListener(ClientPickItemFromBlockPacket packet, Player player) {
        final Instance instance = player.getInstance();
        if (instance == null) return;
        if (!instance.isChunkLoaded(packet.pos())) return;
        final Block block = instance.getBlock(packet.pos());
        final boolean includeData = packet.includeData();

        PlayerPickBlockEvent playerPickBlockEvent = new PlayerPickBlockEvent(player, instance, block, packet.pos().asBlockVec(), includeData);
        player.process().eventHandler().call(playerPickBlockEvent);
    }

    public static void playerPickEntityListener(ClientPickItemFromEntityPacket packet, Player player) {
        final Instance instance = player.getInstance();
        if (instance == null) return;
        final Entity entity = instance.getEntityById(packet.entityId());
        final boolean includeData = packet.includeData();

        PlayerPickEntityEvent playerPickEntityEvent = new PlayerPickEntityEvent(player, entity, includeData);
        player.process().eventHandler().call(playerPickEntityEvent);
    }
}
