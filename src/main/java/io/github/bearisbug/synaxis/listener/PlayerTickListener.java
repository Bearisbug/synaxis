package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerTickEndEvent;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTickEndPacket;

public final class PlayerTickListener {

    public static void listener(ClientTickEndPacket packet, Player player) {
        player.process().eventHandler().call(new PlayerTickEndEvent(player));
    }

}
