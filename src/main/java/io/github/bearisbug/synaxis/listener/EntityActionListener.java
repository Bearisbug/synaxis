package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerLeaveBedEvent;
import io.github.bearisbug.synaxis.event.player.PlayerStartFlyingWithElytraEvent;
import io.github.bearisbug.synaxis.event.player.PlayerStartSprintingEvent;
import io.github.bearisbug.synaxis.event.player.PlayerStopSprintingEvent;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientEntityActionPacket;

public class EntityActionListener {

    public static void listener(ClientEntityActionPacket packet, Player player) {
        switch (packet.action()) {
            case START_SPRINTING -> EntityActionListener.setSprinting(player, true);
            case STOP_SPRINTING -> EntityActionListener.setSprinting(player, false);
            case START_FLYING_ELYTRA -> EntityActionListener.startFlyingElytra(player);
            case LEAVE_BED -> EntityActionListener.onLeaveBed(player);
            // TODO handle the horse actions
            case START_JUMP_HORSE, STOP_JUMP_HORSE, OPEN_HORSE_INVENTORY -> {
            }
        }
    }

    private static void setSprinting(Player player, boolean sprinting) {
        boolean oldState = player.isSprinting();

        player.setSprinting(sprinting);

        if (oldState != sprinting) {
            if (sprinting) {
                player.process().eventHandler().call(new PlayerStartSprintingEvent(player));
            } else {
                player.process().eventHandler().call(new PlayerStopSprintingEvent(player));
            }
        }
    }

    private static void startFlyingElytra(Player player) {
        player.setFlyingWithElytra(true);
        player.process().eventHandler().call(new PlayerStartFlyingWithElytraEvent(player));
    }

    private static void onLeaveBed(Player player) {
        var event = new PlayerLeaveBedEvent(player);
        player.process().eventHandler().callCancellable(event, () -> {
            player.getLivingEntityMeta().setBedInWhichSleepingPosition(null);
            player.leaveBed();
        });
    }
}
