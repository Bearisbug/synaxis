package dev.gameengine.listener;

import dev.gameengine.entity.GameMode;
import dev.gameengine.entity.Player;
import dev.gameengine.event.EventDispatcher;
import dev.gameengine.event.player.PlayerStartFlyingEvent;
import dev.gameengine.event.player.PlayerStopFlyingEvent;
import dev.gameengine.network.packet.client.play.ClientPlayerAbilitiesPacket;

public class AbilitiesListener {

    public static void listener(ClientPlayerAbilitiesPacket packet, Player player) {
        final boolean canFly = player.isAllowFlying() || player.getGameMode() == GameMode.CREATIVE;

        if (canFly) {
            final boolean isFlying = (packet.flags() & 0x2) > 0;

            player.refreshFlying(isFlying);

            if (isFlying) {
                PlayerStartFlyingEvent startFlyingEvent = new PlayerStartFlyingEvent(player);
                EventDispatcher.call(startFlyingEvent);
            } else {
                PlayerStopFlyingEvent stopFlyingEvent = new PlayerStopFlyingEvent(player);
                EventDispatcher.call(stopFlyingEvent);
            }
        }
    }
}
