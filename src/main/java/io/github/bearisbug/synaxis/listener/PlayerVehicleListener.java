package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientVehicleMovePacket;

public class PlayerVehicleListener {

    public static void vehicleMoveListener(ClientVehicleMovePacket packet, Player player) {
        final Entity vehicle = player.getVehicle();
        if (vehicle == null)
            return;

        final var position = packet.position();
        if (!Double.isFinite(position.x()) || !Double.isFinite(position.y()) || !Double.isFinite(position.z()) ||
                Math.abs(position.x()) > Entity.MAX_COORDINATE ||
                Math.abs(position.y()) > Entity.MAX_COORDINATE ||
                Math.abs(position.z()) > Entity.MAX_COORDINATE) {
            player.kick(PlayerPositionListener.KICK_MESSAGE);
            return;
        }

        vehicle.refreshPosition(position);

        // This packet causes weird screen distortion
        /*VehicleMovePacket vehicleMovePacket = new VehicleMovePacket();
        vehicleMovePacket.x = packet.x;
        vehicleMovePacket.y = packet.y;
        vehicleMovePacket.z = packet.z;
        vehicleMovePacket.yaw = packet.yaw;
        vehicleMovePacket.pitch = packet.pitch;
        player.getPlayerConnection().sendPacket(vehicleMovePacket);*/

    }

}
