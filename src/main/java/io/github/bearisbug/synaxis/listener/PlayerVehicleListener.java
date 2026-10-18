package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientVehicleMovePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.VehicleMovePacket;

public class PlayerVehicleListener {

    public static void vehicleMoveListener(ClientVehicleMovePacket packet, Player player) {
        final Entity vehicle = player.getVehicle();
        if (vehicle == null)
            return;

        final var position = packet.position();
        if (PlayerPositionListener.malformed(position)) {
            player.kick(PlayerPositionListener.KICK_MESSAGE);
            return;
        }

        // Turned back as a player's own move is; the vanilla server corrects a vehicle the same way,
        // the client snaps it back
        final Instance instance = vehicle.getInstance();
        final Pos current = vehicle.getPosition();
        if (PlayerPositionListener.turnedBack(instance, current, position)) {
            player.sendPacket(new VehicleMovePacket(current));
            return;
        }

        vehicle.refreshPosition(position);
    }

}
