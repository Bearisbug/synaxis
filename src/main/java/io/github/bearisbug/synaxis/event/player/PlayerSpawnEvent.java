package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.instance.Instance;

/**
 * Called when a new instance is set for a player, once the chunks of the spawn are loaded and the
 * position is sent.
 * <p>
 * A listener owns the player: it runs on the player's tick thread, or on a thread holding the
 * player. The first spawn is dispatched on the tick scheduler thread in the serial part of the tick,
 * before the player is assigned to a tick thread; a listener must not block there, every tick of the
 * process waits for it.
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
     * @deprecated use {@link #getInstance()}. Both are the same when the dispatch starts; once an
     * earlier listener moved the player to another instance, {@code getInstance()} returns that one
     * and this method still returns the instance of this spawn.
     */
    @Deprecated
    public Instance getSpawnInstance() {
        return spawnInstance;
    }

    /**
     * Whether the player was in no instance before this spawn: true for the first spawn after the
     * login, and again for the spawn after every {@link Player#startConfigurationPhase()}; false when
     * the player moves from one instance to another, whatever the dimensions. To know whether this is
     * the first spawn of the session, keep a flag of your own.
     *
     * @return true if the player had no instance before this spawn
     */
    public boolean isFirstSpawn() {
        return firstSpawn;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
