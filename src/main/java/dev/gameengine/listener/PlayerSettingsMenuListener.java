package dev.gameengine.listener;

import dev.gameengine.entity.Player;
import dev.gameengine.event.EventDispatcher;
import dev.gameengine.event.player.PlayerGameRulesRequestEvent;
import dev.gameengine.event.player.PlayerSetGameRulesEvent;
import dev.gameengine.network.packet.client.play.ClientSetGameRulesPacket;
import dev.gameengine.network.packet.client.play.ClientStatusPacket;

public final class PlayerSettingsMenuListener {

    public static void requestGameRules(ClientStatusPacket ignored, Player player) {
        EventDispatcher.call(new PlayerGameRulesRequestEvent(player));
    }

    public static void setGameRules(ClientSetGameRulesPacket packet, Player player) {
        EventDispatcher.call(new PlayerSetGameRulesEvent(player, packet.entries()));
    }

    //todo: add listeners for setting difficulty
}
