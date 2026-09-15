package io.github.bearisbug.synaxis.listener.common;

import io.github.bearisbug.synaxis.network.packet.client.common.ClientCookieResponsePacket;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;

public final class CookieListener {

    public static void handleCookieResponse(ClientCookieResponsePacket packet, PlayerConnection connection) {
        connection.receiveCookieResponse(packet.key(), packet.value());
    }

    private CookieListener() {}
}
