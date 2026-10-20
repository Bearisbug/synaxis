package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Called when a player tries to pick an entity (middle-click).
 * <p>
 * The engine drops a pick of an entity the server does not know, the player does not see or that is out of the
 * player's entity interaction range, by the same rule as an attack, before calling this event; whether to include
 * the data comes from the client as it is.
 */
public class PlayerPickEntityEvent implements PlayerInstanceEvent {

    private final Player player;

    private final Entity entityTarget;
    private final boolean includeData;

    public PlayerPickEntityEvent(Player player, @Nullable Entity entityTarget,
                                 boolean includeData) {
        this.player = player;

        this.entityTarget = entityTarget;
        this.includeData = includeData;
    }

    /**
     * Gets the entity which was picked. Never null when the engine calls the event: the event carries no entity id,
     * so a pick of an entity the server does not know (eg spawned with packets) is dropped instead.
     *
     * @return the entity which was picked
     */
    public @Nullable Entity getTarget() {
        return entityTarget;
    }

    /**
     * Get if the entity data should be included in the result (control middle-click).
     *
     * @return if the entity data should be included.
     */
    public boolean isIncludeData() {
        return this.includeData;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
