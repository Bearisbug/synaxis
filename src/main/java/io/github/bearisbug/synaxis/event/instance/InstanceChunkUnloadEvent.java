package io.github.bearisbug.synaxis.event.instance;

import io.github.bearisbug.synaxis.event.trait.InstanceEvent;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.Instance;

/**
 * Called when a chunk in an instance is unloaded.
 * <p>
 * Called while the chunk is still loaded, holding the monitor of the instance. A listener must not take an entity
 * another tick thread owns ({@code acquirable().sync(...)} or {@code lock()}) nor wait for a future that is not done:
 * that thread may be waiting for this monitor to set a block, and the two threads deadlock. Hand an effect on another
 * entity to the thread of that entity with its scheduler instead.
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
