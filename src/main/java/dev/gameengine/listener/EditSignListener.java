package dev.gameengine.listener;

import dev.gameengine.coordinate.BlockVec;
import dev.gameengine.entity.Player;
import dev.gameengine.event.player.PlayerEditSignEvent;
import dev.gameengine.instance.Instance;
import dev.gameengine.instance.block.Block;
import dev.gameengine.network.packet.client.play.ClientUpdateSignPacket;

public class EditSignListener {
    public static void listener(ClientUpdateSignPacket packet, Player player) {
        Instance instance = player.getInstance();
        if (instance == null) return;
        BlockVec position = packet.blockPosition().asBlockVec();
        if (!instance.isChunkLoaded(position)) return;
        Block block = instance.getBlock(position);
        player.process().eventHandler().call(new PlayerEditSignEvent(
                player,
                instance,
                block,
                position,
                packet.lines(),
                packet.isFrontText()
        ));
    }
}
