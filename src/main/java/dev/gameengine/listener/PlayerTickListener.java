package dev.gameengine.listener;

import dev.gameengine.entity.Player;
import dev.gameengine.event.EventDispatcher;
import dev.gameengine.event.player.PlayerTickEndEvent;
import dev.gameengine.network.packet.client.play.ClientTickEndPacket;

public final class PlayerTickListener {

    public static void listener(ClientTickEndPacket packet, Player player) {
        EventDispatcher.call(new PlayerTickEndEvent(player));
    }

}
