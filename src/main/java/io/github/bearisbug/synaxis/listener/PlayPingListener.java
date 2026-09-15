package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPingRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PingResponsePacket;

public final class PlayPingListener {

    public static void requestListener(ClientPingRequestPacket packet, Player player) {
        player.sendPacket(new PingResponsePacket(packet.number()));
    }
}
