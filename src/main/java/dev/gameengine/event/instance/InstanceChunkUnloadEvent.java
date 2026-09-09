package dev.gameengine.event.instance;

import dev.gameengine.event.trait.InstanceEvent;
import dev.gameengine.instance.Chunk;
import dev.gameengine.instance.Instance;

/**
 * Called when a chunk in an instance is unloaded.
 */
public class InstanceChunkUnloadEvent implements InstanceEvent {

    private final Instance instance;
    private final Chunk chunk;

    public InstanceChunkUnloadEvent(Instance instance, Chunk chunk) {
        this.instance = instance;
        this.chunk = chunk;
    }

    @Override
    public Instance getInstance() {
        return instance;
    }

    /**
     * Gets the chunk X.
     *
     * @return the chunk X
     */
    public int getChunkX() {
        return chunk.getChunkX();
    }

    /**
     * Gets the chunk Z.
     *
     * @return the chunk Z
     */
    public int getChunkZ() {
        return chunk.getChunkZ();
    }

    /**
     * Gets the chunk.
     *
     * @return the chunk.
     */
    public Chunk getChunk() {
        return chunk;
    }
}
