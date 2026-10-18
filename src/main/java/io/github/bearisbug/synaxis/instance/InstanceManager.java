package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.instance.InstanceRegisterEvent;
import io.github.bearisbug.synaxis.event.instance.InstanceUnregisterEvent;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.utils.validate.Check;
import io.github.bearisbug.synaxis.world.DimensionType;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Used to register {@link Instance}.
 */
public final class InstanceManager {

    private final ServerProcess process;
    private final Set<Instance> instances = new CopyOnWriteArraySet<>();

    public InstanceManager(ServerProcess process) {
        this.process = process;
    }

    /**
     * Registers an {@link Instance} internally.
     * <p>
     * Note: not necessary if you created your instance using {@link #createInstanceContainer()}
     * but only if you instantiated your instance object manually
     *
     * @param instance the {@link Instance} to register
     */
    public void registerInstance(Instance instance) {
        UNSAFE_registerInstance(instance);
    }

    /**
     * Creates and register an {@link InstanceContainer} with the specified {@link DimensionType}.
     *
     * @param dimensionType the {@link DimensionType} of the instance
     * @param loader        the chunk loader
     * @return the created {@link InstanceContainer}
     */
    public InstanceContainer createInstanceContainer(RegistryKey<DimensionType> dimensionType, @Nullable ChunkLoader loader) {
        final InstanceContainer instanceContainer = new InstanceContainer(process, UUID.randomUUID(), dimensionType, loader, dimensionType.key());
        registerInstance(instanceContainer);
        return instanceContainer;
    }

    public InstanceContainer createInstanceContainer(RegistryKey<DimensionType> dimensionType) {
        return createInstanceContainer(dimensionType, null);
    }

    public InstanceContainer createInstanceContainer(@Nullable ChunkLoader loader) {
        return createInstanceContainer(DimensionType.OVERWORLD, loader);
    }

    /**
     * Creates and register an {@link InstanceContainer}.
     *
     * @return the created {@link InstanceContainer}
     */
    public InstanceContainer createInstanceContainer() {
        return createInstanceContainer(DimensionType.OVERWORLD, null);
    }

    /**
     * Unregisters the {@link Instance} internally.
     * <p>
     * If {@code instance} is an {@link InstanceContainer} all chunks are unloaded.
     *
     * @param instance the {@link Instance} to unregister
     */
    public void unregisterInstance(Instance instance) {
        long onlinePlayers = instance.getPlayers().stream().filter(Player::isOnline).count();
        Check.stateCondition(onlinePlayers > 0, "You cannot unregister an instance with players inside.");
        // Nothing here holds the instance monitor: a tick thread takes it of its own accord to set a
        // block, so the unregister listeners and the unloading below, which takes the tick thread of
        // every entity it removes, would deadlock with it
        InstanceUnregisterEvent event = new InstanceUnregisterEvent(instance);
        process.eventHandler().call(event);

        // Unregister
        instance.setRegistered(false);
        this.instances.remove(instance);
        // Unload all chunks
        if (instance instanceof InstanceContainer) {
            instance.getChunks().forEach(instance::unloadChunk);
            var dispatcher = process.dispatcher();
            instance.getChunks().forEach(dispatcher::deletePartition);
        }
    }

    /**
     * Gets all the registered instances.
     *
     * @return an unmodifiable {@link Set} containing all the registered instances
     */
    public Set<Instance> getInstances() {
        return Collections.unmodifiableSet(instances);
    }

    /**
     * Gets an instance by the given UUID.
     *
     * @param uuid UUID of the instance
     * @return the instance with the given UUID, null if not found
     */
    public @Nullable Instance getInstance(UUID uuid) {
        Optional<Instance> instance = getInstances()
                .stream()
                .filter(someInstance -> someInstance.getUuid().equals(uuid))
                .findFirst();
        return instance.orElse(null);
    }

    /**
     * Registers an {@link Instance} internally.
     *
     * @param instance the {@link Instance} to register
     */
    private void UNSAFE_registerInstance(Instance instance) {
        instance.setRegistered(true);
        this.instances.add(instance);
        var dispatcher = process.dispatcher();
        instance.getChunks().forEach(dispatcher::createPartition);
        InstanceRegisterEvent event = new InstanceRegisterEvent(instance);
        process.eventHandler().call(event);
    }
}
