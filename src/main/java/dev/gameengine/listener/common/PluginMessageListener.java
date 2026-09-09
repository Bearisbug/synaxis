package dev.gameengine.listener.common;

import dev.gameengine.entity.Player;
import dev.gameengine.event.player.PlayerPluginMessageEvent;
import dev.gameengine.network.packet.client.common.ClientPluginMessagePacket;

public class PluginMessageListener {

    public static void listener(ClientPluginMessagePacket packet, Player player) {
        PlayerPluginMessageEvent pluginMessageEvent = new PlayerPluginMessageEvent(player, packet.channel(), packet.data());
        player.process().eventHandler().call(pluginMessageEvent);
    }

}
