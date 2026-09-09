package dev.gameengine.listener;

import dev.gameengine.entity.Player;
import dev.gameengine.entity.PlayerHand;
import dev.gameengine.event.EventDispatcher;
import dev.gameengine.event.player.PlayerHandAnimationEvent;
import dev.gameengine.network.packet.client.play.ClientAnimationPacket;

public class AnimationListener {

    public static void animationListener(ClientAnimationPacket packet, Player player) {
        final PlayerHand hand = packet.hand();
        PlayerHandAnimationEvent handAnimationEvent = new PlayerHandAnimationEvent(player, hand);
        EventDispatcher.callCancellable(handAnimationEvent, () -> {
            switch (hand) {
                case MAIN -> player.swingMainHand(true);
                case OFF -> player.swingOffHand(true);
            }
        });
    }

}
