package dev.gameengine.listener;

import dev.gameengine.entity.Player;
import dev.gameengine.event.player.PlayerTickEndEvent;
import dev.gameengine.network.packet.client.play.ClientTickEndPacket;

public final class PlayerTickListener {

    public static void listener(ClientTickEndPacket packet, Player player) {
        player.process().eventHandler().call(new PlayerTickEndEvent(player));
    }

}
