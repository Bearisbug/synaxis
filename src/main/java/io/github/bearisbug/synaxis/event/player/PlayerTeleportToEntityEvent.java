package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

import java.util.Objects;

/// Called when a player teleports to another entity, through the spectator hotbar.
///
/// The target is not required to be in the same instance as the player.
public class PlayerTeleportToEntityEvent implements PlayerInstanceEvent {
    private final Player player;
    private final Entity target;

    public PlayerTeleportToEntityEvent(Player player, Entity target) {
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
