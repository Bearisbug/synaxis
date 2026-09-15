package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

/**
 * Called after a chunk being unload to a certain player.
 * <p>
 * Could be used to unload the chunk internally in order to save memory.
 */
public class PlayerChunkUnloadEvent implements PlayerInstanceEvent {

    private final Player player;
    private final int chunkX, chunkZ;

    public PlayerChunkUnloadEvent(Player player, int chunkX, int chunkZ) {
        this.player = player;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    /**
     * Gets the chunk X.
     *
     * @return the chunk X
     */
    public int getChunkX() {
        return chunkX;
    }

    /**
     * Gets the chunk Z.
     *
     * @return the chunk Z
     */
    public int getChunkZ() {
        return chunkZ;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
