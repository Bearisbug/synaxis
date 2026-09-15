package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.event.player.PlayerHandAnimationEvent;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientAnimationPacket;

public class AnimationListener {

    public static void animationListener(ClientAnimationPacket packet, Player player) {
        final PlayerHand hand = packet.hand();
        PlayerHandAnimationEvent handAnimationEvent = new PlayerHandAnimationEvent(player, hand);
        player.process().eventHandler().callCancellable(handAnimationEvent, () -> {
            switch (hand) {
                case MAIN -> player.swingMainHand(true);
                case OFF -> player.swingOffHand(true);
            }
        });
    }

}
