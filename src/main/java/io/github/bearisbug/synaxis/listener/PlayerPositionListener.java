package io.github.bearisbug.synaxis.listener;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.coordinate.RelativeFlags;
import io.github.bearisbug.synaxis.event.player.PlayerMoveEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionAndRotationPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionStatusPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerRotationPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTeleportConfirmPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerPositionAndLookPacket;
import io.github.bearisbug.synaxis.instance.ChunkUtils;
import org.jetbrains.annotations.ApiStatus;

public class PlayerPositionListener {
    static final Component KICK_MESSAGE = Component.text("You moved too far away!");
    /** The farthest one movement packet may go: 4 chunks, past it the client teleported itself. */
    private static final int MAX_MOVE_PER_PACKET = 64;

    public static void playerPacketListener(ClientPlayerPositionStatusPacket packet, Player player) {
        // TODO: Should we expose horizontal collision here and the methods below?
        player.refreshOnGround(packet.onGround());
    }

    /**
     * Exists to update yaw/pitch from UseItemListener
     */
    @ApiStatus.Internal
    public static void playerRotation(Player player, float yaw, float pitch) {
        processMovement(player, player.getPosition().withView(yaw, pitch), player.isOnGround());
    }

    public static void playerLookListener(ClientPlayerRotationPacket packet, Player player) {
        processMovement(player, player.getPosition().withView(packet.yaw(), packet.pitch()), packet.onGround());
    }

    public static void playerPositionListener(ClientPlayerPositionPacket packet, Player player) {
        processMovement(player, player.getPosition().withCoord(packet.position()), packet.onGround());
    }

    public static void playerPositionAndLookListener(ClientPlayerPositionAndRotationPacket packet, Player player) {
        processMovement(player, packet.position(), packet.onGround());
    }

    public static void teleportConfirmListener(ClientTeleportConfirmPacket packet, Player player) {
        player.refreshReceivedTeleportId(packet.teleportId());
    }

    private static void processMovement(Player player, Pos packetPosition, boolean onGround) {
        if (malformed(packetPosition)) {
            player.kick(KICK_MESSAGE);
            return;
        }

        final var currentPosition = player.getPosition();
        if (currentPosition.equals(packetPosition)) {
            // For some reason, the position is the same
            return;
        }
        final Instance instance = player.getInstance();
        // Prevent moving before the player spawned, probably a modified client (or high latency?)
        if (instance == null) {
            return;
        }
        // Prevent the player from moving during a teleport
        if (player.getLastSentTeleportId() != player.getLastReceivedTeleportId()) {
            return;
        }
        if (turnedBack(instance, currentPosition, packetPosition)) {
            var _ = player.teleport(currentPosition);
            return;
        }

        PlayerMoveEvent playerMoveEvent = new PlayerMoveEvent(player, packetPosition, onGround);
        player.process().eventHandler().call(playerMoveEvent);
        if (!currentPosition.equals(player.getPosition())) {
            // Player has been teleported in the event
            return;
        }
        if (playerMoveEvent.isCancelled()) {
            // Teleport to previous position & cancel any velocity
            player.sendPacket(new PlayerPositionAndLookPacket(player.getNextTeleportId(), currentPosition,
                    Vec.ZERO, currentPosition.yaw(), currentPosition.pitch(), (byte) RelativeFlags.NONE));
            return;
        }
        final Pos eventPosition = playerMoveEvent.getNewPosition();
        if (packetPosition.equals(eventPosition)) {
            // Event didn't change the position
            player.refreshPosition(eventPosition);
            player.refreshOnGround(onGround);
        } else {
            // Position modified by the event
            if (packetPosition.samePoint(eventPosition)) {
                player.refreshPosition(eventPosition, true);
                player.refreshOnGround(onGround);
                player.setView(eventPosition.yaw(), eventPosition.pitch());
            } else {
                var _ = player.teleport(eventPosition);
            }
        }
    }

    /**
     * Whether the position a client sent, for its player or for its vehicle, is not one at all: a
     * coordinate or an angle that is not finite, or a coordinate past {@link Entity#MAX_COORDINATE}.
     * Doubles that large overflow or lose their precision; yaw and pitch are raw floats on the wire
     * and would poison every later angle computation, the rotation of the passengers around a
     * vehicle included.
     */
    static boolean malformed(Pos position) {
        return !Double.isFinite(position.x()) || !Double.isFinite(position.y()) || !Double.isFinite(position.z()) ||
                !Float.isFinite(position.yaw()) || !Float.isFinite(position.pitch()) ||
                Math.abs(position.x()) > Entity.MAX_COORDINATE ||
                Math.abs(position.y()) > Entity.MAX_COORDINATE ||
                Math.abs(position.z()) > Entity.MAX_COORDINATE;
    }

    /**
     * Whether a move, of a player or of the vehicle it rides, is turned back where it starts, loading
     * no chunk ({@code docs/DESIGN-PROD.md} {@code API-PROD-030}): a move into a chunk that is not
     * loaded, a move of more than {@value #MAX_MOVE_PER_PACKET} blocks in one packet, and a move out
     * across the world border, or farther out for one the server put outside it.
     */
    static boolean turnedBack(Instance instance, Pos from, Pos to) {
        return (!from.sameChunk(to) && !ChunkUtils.isLoaded(instance, to))
                || from.distanceSquared(to) > MAX_MOVE_PER_PACKET * MAX_MOVE_PER_PACKET
                || outwards(instance, from, to);
    }

    /**
     * Whether a move leaves the world border of the instance or, for a player the server put outside
     * it, gets farther out on the x or the z axis; turning, walking along the border and walking back
     * in do not. The border ends at the wall the vanilla client collides with, its minimum rounded
     * down and its maximum up (26.2 {@code WorldBorder$StaticBorderExtent#updateBox}), so that a
     * client against the wall of a border off the block grid is inside.
     * <p>
     * While a border shrinks over time ({@code Instance#setWorldBorder(border, transitionTime)}), the
     * server moves its edges every tick from the call on and the client only once the packet reached
     * it, so the edge judged here runs ahead of the client's wall by about the speed of the edge times
     * the round trip time, and a player walking against the wall is turned back now and then (worked
     * out from the code, not run; no deployment shrinks its border over time).
     */
    private static boolean outwards(Instance instance, Pos from, Pos to) {
        final var border = instance.getWorldBorder();
        final double radius = border.diameter() / 2;
        final double minX = Math.floor(border.centerX() - radius), maxX = Math.ceil(border.centerX() + radius);
        final double minZ = Math.floor(border.centerZ() - radius), maxZ = Math.ceil(border.centerZ() + radius);
        if (from.x() >= minX && from.x() < maxX && from.z() >= minZ && from.z() < maxZ) {
            return to.x() < minX || to.x() >= maxX || to.z() < minZ || to.z() >= maxZ;
        }
        return beyond(to.x(), minX, maxX) > beyond(from.x(), minX, maxX)
                || beyond(to.z(), minZ, maxZ) > beyond(from.z(), minZ, maxZ);
    }

    /** How far {@code coordinate} is outside [{@code min}, {@code max}], 0 within it. */
    private static double beyond(double coordinate, double min, double max) {
        return Math.max(0, Math.max(min - coordinate, coordinate - max));
    }
}
