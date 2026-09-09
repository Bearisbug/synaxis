package dev.gameengine.listener.common;

import dev.gameengine.entity.Player;
import dev.gameengine.event.player.PlayerSettingsChangeEvent;
import dev.gameengine.network.packet.client.common.ClientSettingsPacket;

public final class SettingsListener {
    public static void listener(ClientSettingsPacket packet, Player player) {
        // Since viewDistance bounds checking is performed in the refresh function, it is not necessary to check it here
        player.refreshSettings(packet.settings());
        player.process().eventHandler().call(new PlayerSettingsChangeEvent(player));
    }
}
