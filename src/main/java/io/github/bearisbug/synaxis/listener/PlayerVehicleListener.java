package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.ChunkUtils;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientVehicleMovePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.VehicleMovePacket;

public class PlayerVehicleListener {

    public static void vehicleMoveListener(ClientVehicleMovePacket packet, Player player) {
        final Entity vehicle = player.getVehicle();
        if (vehicle == null)
            return;

        final var position = packet.position();
        // Yaw and pitch are raw floats on the wire and the passenger offset is rotated around the
        // vehicle yaw, so a non finite view would poison the position of every rider
        if (!Double.isFinite(position.x()) || !Double.isFinite(position.y()) || !Double.isFinite(position.z()) ||
                !Float.isFinite(position.yaw()) || !Float.isFinite(position.pitch()) ||
                Math.abs(position.x()) > Entity.MAX_COORDINATE ||
                Math.abs(position.y()) > Entity.MAX_COORDINATE ||
                Math.abs(position.z()) > Entity.MAX_COORDINATE) {
            player.kick(PlayerPositionListener.KICK_MESSAGE);
            return;
        }

        // Turned back where the vehicle is, loading no chunk, as a player's own move is: a move into a
        // chunk that is not loaded, a move of more than 64 blocks in one packet and a move out across the
        // world border, or farther out for a vehicle the server put outside it. The vanilla server
        // corrects a vehicle the same way, the client snaps it back.
        final Instance instance = vehicle.getInstance();
        final Pos current = vehicle.getPosition();
        if ((!current.sameChunk(position) && !ChunkUtils.isLoaded(instance, position))
                || current.distanceSquared(position) > 64 * 64
                || PlayerPositionListener.outwards(instance, current, position)) {
            player.sendPacket(new VehicleMovePacket(current));
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
