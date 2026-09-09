package net.minestom.server.listener;

import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.client.play.ClientStatusPacket;

public class PlayStatusListener {

    public static void listener(ClientStatusPacket packet, Player player) {
        switch (packet.action()) {
            case PERFORM_RESPAWN -> player.respawn();
            // 引擎不记录玩家统计数据，统计请求无内容可回。
            case REQUEST_STATS -> {
            }
            case REQUEST_GAMERULE_VALUES -> PlayerSettingsMenuListener.requestGameRules(packet, player);
        }
    }
}
