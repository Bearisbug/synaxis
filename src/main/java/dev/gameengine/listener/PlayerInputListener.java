package dev.gameengine.listener;

import dev.gameengine.entity.Player;
import dev.gameengine.network.packet.client.play.ClientInputPacket;

public class PlayerInputListener {

    public static void listener(ClientInputPacket packet, Player player) {
        player.refreshInput(
                packet.forward(), packet.backward(),
                packet.left(), packet.right(),
                packet.jump(),
                packet.shift(),
                packet.sprint()
        );
    }

}
