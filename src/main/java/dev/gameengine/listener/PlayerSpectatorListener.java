package dev.gameengine.listener;

import dev.gameengine.MinecraftServer;
import dev.gameengine.entity.Entity;
import dev.gameengine.entity.GameMode;
import dev.gameengine.entity.Player;
import dev.gameengine.event.EventDispatcher;
import dev.gameengine.event.player.PlayerSpectateEntityEvent;
import dev.gameengine.event.player.PlayerTeleportToEntityEvent;
import dev.gameengine.instance.Instance;
import dev.gameengine.network.packet.client.play.ClientSpectatorActionPacket;
import dev.gameengine.network.packet.client.play.ClientTeleportToEntityPacket;

import java.util.UUID;

public class PlayerSpectatorListener {

    public static void listener(ClientSpectatorActionPacket packet, Player player) {
        // Ignore if the player is not in spectator mode
        if (player.getGameMode() != GameMode.SPECTATOR) {
            return;
        }

        final Integer targetId = packet.targetId();
        if (targetId == null) return;
        final Instance instance = player.getInstance();
        if (instance == null) return;
        final Entity target = instance.getEntityById(targetId);

        // Check if the target is valid, and the use is allowed
        if (target == null || target == player || UseEntityListener.invalidUse(player, target)) {
            return;
        }

        EventDispatcher.call(new PlayerSpectateEntityEvent(player, target));
    }

    public static void listener(ClientTeleportToEntityPacket packet, Player player) {
        // Ignore if the player is not in spectator mode
        if (player.getGameMode() != GameMode.SPECTATOR) {
            return;
        }

        final UUID targetUuid = packet.target();
        final Instance playerInstance = player.getInstance();
        if (playerInstance == null) return;
        Entity target = playerInstance.getEntityByUuid(targetUuid);

        // If the target is not found, try to find it in other instances
        if (target == null) {
            for (Instance instance : MinecraftServer.getInstanceManager().getInstances()) {
                if (instance == playerInstance) continue;
                target = instance.getEntityByUuid(targetUuid);
                if (target != null) break;
            }
        }

        // Check if the target is valid
        if (target == null || target == player) {
            return;
        }

        EventDispatcher.call(new PlayerTeleportToEntityEvent(player, target));
    }
}
