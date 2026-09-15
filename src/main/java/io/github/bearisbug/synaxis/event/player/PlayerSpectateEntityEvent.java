package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

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
