package io.github.bearisbug.synaxis.listener.common;

import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.network.DisconnectReasons;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientCookieResponsePacket;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;

public final class CookieListener {

    public static void handleCookieResponse(ClientCookieResponsePacket packet, PlayerConnection connection) {
        final String key = packet.key();
        // The key is only checked for length while decoding, and looking one up parses it: an
        // unparseable key would be an exception reported for every packet carrying one.
        if (!Key.parseable(key)) {
            connection.kick(DisconnectReasons.PROTOCOL_ERROR);
            return;
        }
        connection.receiveCookieResponse(key, packet.value());
    }

    private CookieListener() {}
}
