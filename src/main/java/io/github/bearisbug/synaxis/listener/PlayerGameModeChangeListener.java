package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerGameModeRequestEvent;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChangeGameModePacket;

public final class PlayerGameModeChangeListener {

    public static void listener(ClientChangeGameModePacket packet, Player player) {
        PlayerGameModeRequestEvent playerGameModeRequestEvent = new PlayerGameModeRequestEvent(player, packet.gameMode());
        player.process().eventHandler().call(playerGameModeRequestEvent);
    }
}
