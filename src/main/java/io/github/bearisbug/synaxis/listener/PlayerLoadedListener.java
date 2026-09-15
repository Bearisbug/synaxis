package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerLoadedEvent;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerLoadedPacket;

public final class PlayerLoadedListener {

    public static void listener(ClientPlayerLoadedPacket packet, Player player) {
        player.process().eventHandler().call(new PlayerLoadedEvent(player));
    }

}
