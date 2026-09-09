package dev.gameengine.listener.common;

import dev.gameengine.network.packet.client.common.ClientCookieResponsePacket;
import dev.gameengine.network.player.PlayerConnection;

public final class CookieListener {

    public static void handleCookieResponse(ClientCookieResponsePacket packet, PlayerConnection connection) {
        connection.receiveCookieResponse(packet.key(), packet.value());
    }

    private CookieListener() {}
}
