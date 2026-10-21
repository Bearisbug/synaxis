package io.github.bearisbug.synaxis.event.instance;

import io.github.bearisbug.synaxis.event.trait.InstanceEvent;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.Instance;

/**
 * Called when a chunk in an instance is loaded.
 * <p>
 * Called by the thread that completed the load, usually a virtual thread, possibly the thread that asked for the
 * chunk; do not rely on running on a tick thread. Hand an effect on an entity to the thread of that entity with its
 * scheduler instead of touching it here.
 */
public class InstanceChunkLoadEvent implements InstanceEvent {

    private final Instance instance;
    private final Chunk chunk;

    public InstanceChunkLoadEvent(Instance instance, Chunk chunk) {
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
