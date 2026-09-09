package dev.gameengine.listener.common;

import dev.gameengine.entity.Player;
import dev.gameengine.event.player.PlayerResourcePackStatusEvent;
import dev.gameengine.network.packet.client.common.ClientResourcePackStatusPacket;

public class ResourcePackListener {

    public static void listener(ClientResourcePackStatusPacket packet, Player player) {
        player.process().eventHandler().call(new PlayerResourcePackStatusEvent(player, packet.id(), packet.status()));
        if (!player.isOnline()) return;

        // Run adventure callbacks for the resource pack
        player.onResourcePackStatus(packet.id(), packet.status());
    }
}
