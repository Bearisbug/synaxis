package dev.gameengine.event.player;

import dev.gameengine.entity.Entity;
import dev.gameengine.entity.Player;
import dev.gameengine.event.trait.PlayerInstanceEvent;

import java.util.Objects;

/// Called when a Player tries to spectate an entity (start looking through its eyes).
///
/// Only called when the Player is in the spectator gamemode and if the target is reachable from the current instance.
@SuppressWarnings("ClassCanBeRecord")
public class PlayerSpectateEntityEvent implements PlayerInstanceEvent {
    private final Player player;
    private final Entity target;

    public PlayerSpectateEntityEvent(Player player, Entity target) {
        this.player = Objects.requireNonNull(player, "player");
        this.target = Objects.requireNonNull(target, "target");
    }

    public Entity getTarget() {
        return target;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
