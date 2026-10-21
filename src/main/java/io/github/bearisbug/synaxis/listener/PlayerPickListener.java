package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerPickBlockEvent;
import io.github.bearisbug.synaxis.event.player.PlayerPickEntityEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPickItemFromBlockPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPickItemFromEntityPacket;

public class PlayerPickListener {

    public static void playerPickBlockListener(ClientPickItemFromBlockPacket packet, Player player) {
        final Instance instance = player.getInstance();
        if (instance == null) return;
        if (!instance.isChunkLoaded(packet.pos())) return;
        if (PlayerActionListener.outOfReach(player, packet.pos())) return;
        final Block block = instance.getBlock(packet.pos());
        final boolean includeData = packet.includeData();

        PlayerPickBlockEvent playerPickBlockEvent = new PlayerPickBlockEvent(player, instance, block, packet.pos().asBlockVec(), includeData);
        player.process().eventHandler().call(playerPickBlockEvent);
    }

    public static void playerPickEntityListener(ClientPickItemFromEntityPacket packet, Player player) {
        final Instance instance = player.getInstance();
        if (instance == null) return;
        final Entity entity = instance.getEntityById(packet.entityId());
        if (entity == null || UseEntityListener.invalidUse(player, entity)) return;
        final boolean includeData = packet.includeData();

        PlayerPickEntityEvent playerPickEntityEvent = new PlayerPickEntityEvent(player, entity, includeData);
        player.process().eventHandler().call(playerPickEntityEvent);
    }
}
