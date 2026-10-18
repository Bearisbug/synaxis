package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerEditSignEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientUpdateSignPacket;

public class EditSignListener {
    public static void listener(ClientUpdateSignPacket packet, Player player) {
        Instance instance = player.getInstance();
        if (instance == null) return;
        BlockVec position = packet.blockPosition().asBlockVec();
        if (!instance.isChunkLoaded(position)) return;
        // A sign edit is a block use like the others (docs/DESIGN-PROD.md API-PROD-030)
        if (PlayerActionListener.outOfReach(player, position)) return;
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
