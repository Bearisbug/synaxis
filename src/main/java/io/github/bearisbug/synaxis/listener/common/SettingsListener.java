package io.github.bearisbug.synaxis.listener.common;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerSettingsChangeEvent;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientSettingsPacket;

public final class SettingsListener {
    public static void listener(ClientSettingsPacket packet, Player player) {
        // The ClientSettings constructor clamps viewDistance to [2, 32]: the decoded value is already valid
        player.refreshSettings(packet.settings());
        player.process().eventHandler().call(new PlayerSettingsChangeEvent(player));
    }
}
