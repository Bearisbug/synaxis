package dev.gameengine.listener;

import dev.gameengine.entity.Player;
import dev.gameengine.event.player.PlayerGameModeRequestEvent;
import dev.gameengine.network.packet.client.play.ClientChangeGameModePacket;

public final class PlayerGameModeChangeListener {

    public static void listener(ClientChangeGameModePacket packet, Player player) {
        PlayerGameModeRequestEvent playerGameModeRequestEvent = new PlayerGameModeRequestEvent(player, packet.gameMode());
        player.process().eventHandler().call(playerGameModeRequestEvent);
    }
}
