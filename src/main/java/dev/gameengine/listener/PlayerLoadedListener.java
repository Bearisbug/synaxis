package dev.gameengine.listener;

import dev.gameengine.entity.Player;
import dev.gameengine.event.player.PlayerLoadedEvent;
import dev.gameengine.network.packet.client.play.ClientPlayerLoadedPacket;

public final class PlayerLoadedListener {

    public static void listener(ClientPlayerLoadedPacket packet, Player player) {
        player.process().eventHandler().call(new PlayerLoadedEvent(player));
    }

}
