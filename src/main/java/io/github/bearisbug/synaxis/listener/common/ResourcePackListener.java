package io.github.bearisbug.synaxis.listener.common;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerResourcePackStatusEvent;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientResourcePackStatusPacket;

public class ResourcePackListener {

    public static void listener(ClientResourcePackStatusPacket packet, Player player) {
        player.process().eventHandler().call(new PlayerResourcePackStatusEvent(player, packet.id(), packet.status()));
        if (!player.isOnline()) return;

        // Run adventure callbacks for the resource pack
        player.onResourcePackStatus(packet.id(), packet.status());
    }
}
