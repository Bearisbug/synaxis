package io.github.bearisbug.synaxis.listener.common;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent;
import io.github.bearisbug.synaxis.network.DisconnectReasons;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPluginMessagePacket;
import net.kyori.adventure.key.Key;

public class PluginMessageListener {

    public static void listener(ClientPluginMessagePacket packet, Player player) {
        // The channel is only checked for length while decoding; the vanilla and Fabric clients only send
        // identifiers, and anything else would reach every listener and log line as the client wrote it
        if (!Key.parseable(packet.channel())) {
            player.getPlayerConnection().kick(DisconnectReasons.PROTOCOL_ERROR);
            return;
        }
        PlayerPluginMessageEvent pluginMessageEvent = new PlayerPluginMessageEvent(player, packet.channel(), packet.data());
        player.process().eventHandler().call(pluginMessageEvent);
    }

}
