package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerGameRulesRequestEvent;
import io.github.bearisbug.synaxis.event.player.PlayerSetGameRulesEvent;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSetGameRulesPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientStatusPacket;

public final class PlayerSettingsMenuListener {

    public static void requestGameRules(ClientStatusPacket ignored, Player player) {
        player.process().eventHandler().call(new PlayerGameRulesRequestEvent(player));
    }

    public static void setGameRules(ClientSetGameRulesPacket packet, Player player) {
        player.process().eventHandler().call(new PlayerSetGameRulesEvent(player, packet.entries()));
    }

    //todo: add listeners for setting difficulty
}
