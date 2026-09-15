package io.github.bearisbug.synaxis.listener.common;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPluginMessagePacket;

public class PluginMessageListener {

    public static void listener(ClientPluginMessagePacket packet, Player player) {
        PlayerPluginMessageEvent pluginMessageEvent = new PlayerPluginMessageEvent(player, packet.channel(), packet.data());
        player.process().eventHandler().call(pluginMessageEvent);
    }

}
