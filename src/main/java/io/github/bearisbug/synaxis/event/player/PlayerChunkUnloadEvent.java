package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

/**
 * Called when a chunk leaves the chunk ring of a player, the chunks the server counts as on its
 * client: the ring moves away from it, the player changes instance, respawns or is removed. A chunk
 * that got a {@link PlayerChunkLoadEvent} gets exactly one of these when it leaves the ring; a chunk
 * of the ring that was not sent yet may get one too. It is called while the player is still in the
 * instance of the chunk, before the ring moves.
 * <p>
 * It only tells: the client may keep the chunk (a chunk still in the view after an instance change of
 * the same dimension is not unloaded on the client), and the server does not unload it for that.
 * Whether the chunk unloads is up to the tickets of the instance and their grace period, do not call
 * {@link io.github.bearisbug.synaxis.instance.Instance#unloadChunk(int, int)} from here: other players
 * may still have it. When the chunk itself unloads, the player gets no event.
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
