package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerStartFlyingEvent;
import io.github.bearisbug.synaxis.event.player.PlayerStopFlyingEvent;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerAbilitiesPacket;

public class AbilitiesListener {

    public static void listener(ClientPlayerAbilitiesPacket packet, Player player) {
        final boolean canFly = player.isAllowFlying() || player.getGameMode() == GameMode.CREATIVE;

        if (canFly) {
            final boolean isFlying = (packet.flags() & 0x2) > 0;

            player.refreshFlying(isFlying);

            if (isFlying) {
                PlayerStartFlyingEvent startFlyingEvent = new PlayerStartFlyingEvent(player);
                player.process().eventHandler().call(startFlyingEvent);
            } else {
                PlayerStopFlyingEvent stopFlyingEvent = new PlayerStopFlyingEvent(player);
                player.process().eventHandler().call(stopFlyingEvent);
            }
        }
    }
}
