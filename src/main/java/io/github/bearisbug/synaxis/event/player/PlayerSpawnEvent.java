package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.instance.Instance;

/**
 * Called when a new instance is set for a player.
 */
public class PlayerSpawnEvent implements PlayerInstanceEvent {
    private final Player player;
    private final Instance spawnInstance;
    private final boolean firstSpawn;

    public PlayerSpawnEvent(Player player, Instance spawnInstance, boolean firstSpawn) {
        this.player = player;
        this.spawnInstance = spawnInstance;
        this.firstSpawn = firstSpawn;
    }

    /**
     * Gets the player's new instance.
     *
     * @return the instance
     */
    @Deprecated
    public Instance getSpawnInstance() {
        return spawnInstance;
    }

    /**
     * 'true' if the player is spawning for the first time. 'false' if this spawn event was triggered by a dimension teleport
     *
     * @return true if this is the first spawn, false otherwise
     */
    public boolean isFirstSpawn() {
        return firstSpawn;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
