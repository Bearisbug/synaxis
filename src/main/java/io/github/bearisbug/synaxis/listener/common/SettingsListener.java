package io.github.bearisbug.synaxis.listener.common;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerSettingsChangeEvent;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientSettingsPacket;

public final class SettingsListener {
    public static void listener(ClientSettingsPacket packet, Player player) {
        // Since viewDistance bounds checking is performed in the refresh function, it is not necessary to check it here
        player.refreshSettings(packet.settings());
        player.process().eventHandler().call(new PlayerSettingsChangeEvent(player));
    }
}
