package dev.gameengine.listener;

import net.kyori.adventure.text.Component;
import dev.gameengine.coordinate.Pos;
import dev.gameengine.coordinate.Vec;
import dev.gameengine.entity.Entity;
import dev.gameengine.entity.Player;
import dev.gameengine.entity.RelativeFlags;
import dev.gameengine.event.EventDispatcher;
import dev.gameengine.event.player.PlayerMoveEvent;
import dev.gameengine.instance.Instance;
import dev.gameengine.network.packet.client.play.ClientPlayerPositionAndRotationPacket;
import dev.gameengine.network.packet.client.play.ClientPlayerPositionPacket;
import dev.gameengine.network.packet.client.play.ClientPlayerPositionStatusPacket;
import dev.gameengine.network.packet.client.play.ClientPlayerRotationPacket;
import dev.gameengine.network.packet.client.play.ClientTeleportConfirmPacket;
import dev.gameengine.network.packet.server.play.PlayerPositionAndLookPacket;
import dev.gameengine.utils.chunk.ChunkUtils;
import org.jetbrains.annotations.ApiStatus;

public class PlayerPositionListener {
    static final Component KICK_MESSAGE = Component.text("You moved too far away!");

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
        // Prevent the player from moving too far
        // Doubles close to max size can cause overflow, or simply have precision issues
        // Yaw and pitch are raw floats on the wire and would poison every later angle computation
        if (!Double.isFinite(packetPosition.x()) || !Double.isFinite(packetPosition.y()) || !Double.isFinite(packetPosition.z()) ||
                !Float.isFinite(packetPosition.yaw()) || !Float.isFinite(packetPosition.pitch()) ||
                Math.abs(packetPosition.x()) > Entity.MAX_COORDINATE ||
                Math.abs(packetPosition.y()) > Entity.MAX_COORDINATE ||
                Math.abs(packetPosition.z()) > Entity.MAX_COORDINATE) {
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
        // Try to move in an unloaded chunk, prevent it
        if (!currentPosition.sameChunk(packetPosition) && !ChunkUtils.isLoaded(instance, packetPosition)) {
            var _ = player.teleport(currentPosition);
            return;
        }

        PlayerMoveEvent playerMoveEvent = new PlayerMoveEvent(player, packetPosition, onGround);
        EventDispatcher.call(playerMoveEvent);
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
}
