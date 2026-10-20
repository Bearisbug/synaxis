package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.Viewable;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.ExperienceOrb;
import io.github.bearisbug.synaxis.entity.ItemEntity;
import io.github.bearisbug.synaxis.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Defines how {@link Entity entities} are tracked within an {@link Instance instance}.
 * <p>
 * Implementations are expected to be thread-safe.
 */
public sealed interface EntityTracker permits EntityTrackerImpl {
    static EntityTracker newTracker(int entityViewDistance) {
        return new EntityTrackerImpl(entityViewDistance);
    }

    /**
     * Registers an entity to be tracked at {@code point}. Called by {@link Entity#setInstance}: plugins and
     * game code place an entity with that method, a direct call conflicts with the entity's own
     * registration. The register, move and unregister calls of one entity are made one after another
     * by the thread owning it.
     *
     * @param entity the entity to track
     * @param point  the position of the entity
     * @param target the kind of nearby entities {@code update} is told; the entity itself is tracked
     *               under every {@link Target} its type matches
     * @param update told {@link Update#referenceUpdate} with this tracker, then each other {@code target}
     *               entity in the {@code entityViewDistance} square of chunks around {@code point}; null for none
     * @throws IllegalStateException if an entity with the same id or the same uuid is registered already
     */
    <T extends Entity> void register(Entity entity, Point point,
                                     Target<T> target, @Nullable Update<T> update);

    /**
     * Stops tracking an entity; nothing happens for one not tracked here. Called by {@link Entity#remove()}
     * and by {@link Entity#setInstance} when the entity leaves this instance, see
     * {@link #register(Entity, Point, Target, Update)}.
     *
     * @param entity the entity to stop tracking
     * @param target the kind of nearby entities {@code update} is told
     * @param update told {@link Update#referenceUpdate} with a null tracker, then each other {@code target}
     *               entity in the {@code entityViewDistance} square of chunks around the last position
     *               of the entity; null for none
     */
    <T extends Entity> void unregister(Entity entity, Target<T> target, @Nullable Update<T> update);

    /**
     * Gets an entity based on its id (from {@link Entity#getEntityId()}).
     *
     * @param id the entity id
     * @return the entity having the specified id, null if not found
     */
    @Nullable Entity getEntityById(int id);

    /**
     * Gets an entity based on its UUID (from {@link Entity#getUuid()}).
     *
     * @param uuid the entity UUID
     * @return the entity having the specified uuid, null if not found
     */
    @Nullable Entity getEntityByUuid(UUID uuid);

    /**
     * Records the new position of an entity; one not tracked here is ignored. Called by
     * {@link Entity#teleport}, {@link Entity#moveTo} and {@link Entity#refreshPosition}, see
     * {@link #register(Entity, Point, Target, Update)}.
     * <p>
     * Within the same chunk only the recorded position changes. Into another chunk, the entity moves to
     * the bucket of that chunk, and {@code update} is told the {@code target} entities entering and
     * leaving the {@code entityViewDistance} square of chunks around it.
     *
     * @param entity   the entity that moved
     * @param newPoint its new position
     * @param target   the kind of nearby entities {@code update} is told
     * @param update   told the entities entering and leaving the square, then
     *                 {@link Update#referenceUpdate} with this tracker; null for none
     */
    <T extends Entity> void move(Entity entity, Point newPoint,
                                 Target<T> target, @Nullable Update<T> update);

    /**
     * The entities of {@code target} in a chunk, as a read-only view of the current bucket of that chunk,
     * to use within the call that got it. Once the chunk holds none of them the bucket is dropped, and
     * a new one is made for the next entity: a kept view does not follow. Copy it to keep it.
     */
    @UnmodifiableView <T extends Entity> Collection<T> chunkEntities(int chunkX, int chunkZ, Target<T> target);

    /**
     * The entities of {@code target} in the chunk of {@code point}, see {@link #chunkEntities(int, int, Target)}.
     */
    @UnmodifiableView
    default <T extends Entity> Collection<T> chunkEntities(Point point, Target<T> target) {
        return chunkEntities(point.chunkX(), point.chunkZ(), target);
    }

    /**
     * Calls {@code query} with every entity of {@code target} in the {@code (2 * chunkRange + 1)²} chunks
     * of the square centered on the chunk of {@code point}, with no distance filter; with none for a
     * negative {@code chunkRange}. {@code query} runs on the calling thread before this method returns.
     *
     * @throws IllegalArgumentException if {@code chunkRange} is above 23169
     */
    <T extends Entity> void nearbyEntitiesByChunkRange(Point point, int chunkRange,
                                                       Target<T> target, Consumer<T> query);

    /**
     * Calls {@code query} with every entity of {@code target} whose position recorded here, the last one
     * it was registered or moved to, is within {@code range} blocks of {@code point}: the euclidean
     * distance in three dimensions, y included. {@code range} is to be non-negative and finite;
     * {@link Double#MAX_VALUE} finds every entity.
     * <p>
     * {@code query} runs on the calling thread before this method returns, while other tick threads may
     * move the entities.
     */
    <T extends Entity> void nearbyEntities(Point point, double range,
                                           Target<T> target, Consumer<T> query);

    /**
     * Gets all the entities tracked by this class.
     */
    @UnmodifiableView
    <T extends Entity> Set<T> entities(Target<T> target);

    @UnmodifiableView
    default Set<Entity> entities() {
        return entities(Target.ENTITIES);
    }

    /**
     * The viewers of the chunk at {@code chunkX, chunkZ}, one object per coordinate. Taken by the
     * {@link Chunk} constructor and released through {@link #unregisterViewable(Viewable)} when the chunk
     * unloads; one taken for a coordinate with no chunk stays in this tracker until a chunk there unloads.
     */
    Viewable viewable(int chunkX, int chunkZ);

    /**
     * Releases a {@link Viewable} previously obtained from {@link #viewable(int, int)},
     * so that it stops being retained by this tracker.
     *
     * @param viewable the viewable to unregister
     */
    @ApiStatus.Internal
    void unregisterViewable(Viewable viewable);

    /**
     * Represents the type of entity you want to retrieve.
     *
     * @param <E> the entity type
     */
    @ApiStatus.NonExtendable
    interface Target<E extends Entity> {
        Target<Entity> ENTITIES = create(Entity.class);
        Target<Player> PLAYERS = create(Player.class);
        Target<ItemEntity> ITEMS = create(ItemEntity.class);
        Target<ExperienceOrb> EXPERIENCE_ORBS = create(ExperienceOrb.class);

        List<EntityTracker.Target<? extends Entity>> TARGETS = List.of(EntityTracker.Target.ENTITIES, EntityTracker.Target.PLAYERS, EntityTracker.Target.ITEMS, EntityTracker.Target.EXPERIENCE_ORBS);

        Class<E> type();

        int ordinal();

        private static <T extends Entity> EntityTracker.Target<T> create(Class<T> type) {
            final int ordinal = EntityTrackerImpl.TARGET_COUNTER.getAndIncrement();
            return new Target<>() {
                @Override
                public Class<T> type() {
                    return type;
                }

                @Override
                public int ordinal() {
                    return ordinal;
                }
            };
        }
    }

    /**
     * Callback to know the newly visible entities and those to remove.
     */
    interface Update<E extends Entity> {
        void add(E entity);

        void remove(E entity);

        /**
         * Called with the position the entity is tracked at and this tracker after a register or a move
         * into another chunk, and with its last position and a null tracker once it is unregistered.
         */
        default void referenceUpdate(Point point, @Nullable EntityTracker tracker) {
            // Empty
        }
    }
}
