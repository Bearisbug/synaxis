package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientInputPacket;

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
