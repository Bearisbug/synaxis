package dev.gameengine.listener;

import dev.gameengine.entity.Player;
import dev.gameengine.network.packet.client.common.ClientPingRequestPacket;
import dev.gameengine.network.packet.server.common.PingResponsePacket;

public final class PlayPingListener {

    public static void requestListener(ClientPingRequestPacket packet, Player player) {
        player.sendPacket(new PingResponsePacket(packet.number()));
    }
}
