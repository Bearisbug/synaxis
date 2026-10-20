package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.Viewable;
import io.github.bearisbug.synaxis.coordinate.ChunkRange;
import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.vectrix.flare.fastutil.Int2ObjectSyncMap;
import space.vectrix.flare.fastutil.Long2ObjectSyncMap;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

final class EntityTrackerImpl implements EntityTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(EntityTrackerImpl.class);

    static final AtomicInteger TARGET_COUNTER = new AtomicInteger();

    private static final long UNREGISTERED_MOVE_REPORT_INTERVAL = TimeUnit.SECONDS.toNanos(10);

    // Store all data associated to a Target
    // The array index is Target#ordinal(), which TARGET_COUNTER hands out in the order the Target
    // constants initialize, the order of the TARGETS list
    @SuppressWarnings("unchecked")
    final TargetEntry<Entity>[] targetEntries = EntityTracker.Target.TARGETS.stream().map((Function<Target<?>, TargetEntry<?>>) TargetEntry::new).toArray(TargetEntry[]::new);

    private final int entityViewDistance;

    EntityTrackerImpl(int entityViewDistance) {
        this.entityViewDistance = entityViewDistance;
    }

    private final Int2ObjectSyncMap<EntityTrackerEntry> entriesByEntityId = Int2ObjectSyncMap.hashmap();
    private final Map<UUID, EntityTrackerEntry> entriesByEntityUuid = new ConcurrentHashMap<>();
    // Chunk index -> viewers of that chunk
    final Map<Long, ChunkView> chunkViews = new ConcurrentHashMap<>();

    // Move attempts on untracked entities not written to the log yet, drained by every report
    final AtomicLong unregisteredMoveAttempts = new AtomicLong();
    private final AtomicLong lastUnregisteredMoveReport =
            new AtomicLong(System.nanoTime() - UNREGISTERED_MOVE_REPORT_INTERVAL);

    @Override
    public <T extends Entity> void register(Entity entity, Point point,
                                            Target<T> target, @Nullable Update<T> update) {
        EntityTrackerEntry newEntry = new EntityTrackerEntry(entity, point);

        EntityTrackerEntry prevEntryWithId = entriesByEntityId.putIfAbsent(entity.getEntityId(), newEntry);
        Check.stateCondition(prevEntryWithId != null, "There is already an entity registered with id {0}", entity.getEntityId());
        EntityTrackerEntry prevEntryWithUuid = entriesByEntityUuid.putIfAbsent(entity.getUuid(), newEntry);
        // A rejected registration must leave no trace, the entity is never unregistered to clean it up
        if (prevEntryWithUuid != null) entriesByEntityId.remove(entity.getEntityId(), newEntry);
        Check.stateCondition(prevEntryWithUuid != null, "There is already an entity registered with uuid {0}", entity.getUuid());

        final long index = CoordConversion.chunkIndex(point);
        for (TargetEntry<Entity> targetEntry : targetEntries) {
            if (targetEntry.target.type().isInstance(entity)) {
                targetEntry.entities.add(entity);
                targetEntry.addToChunk(index, entity);
            }
        }
        if (update != null) {
            update.referenceUpdate(point, this);
            nearbyEntitiesByChunkRange(point, entityViewDistance, target, newEntity -> {
                if (newEntity == entity) return;
                update.add(newEntity);
            });
        }
    }

    @Override
    public <T extends Entity> void unregister(Entity entity,
                                              Target<T> target, @Nullable Update<T> update) {
        EntityTrackerEntry entry = entriesByEntityId.remove(entity.getEntityId());
        if (entry == null) return;
        // Only drop the uuid index when it points at this entity, another one may own that uuid
        entriesByEntityUuid.remove(entity.getUuid(), entry);
        final Point point = entry.getLastPosition();

        final long index = CoordConversion.chunkIndex(point);
        for (TargetEntry<Entity> targetEntry : targetEntries) {
            if (targetEntry.target.type().isInstance(entity)) {
                targetEntry.entities.remove(entity);
                targetEntry.removeFromChunk(index, entity);
            }
        }
        if (update != null) {
            update.referenceUpdate(point, null);
            nearbyEntitiesByChunkRange(point, entityViewDistance, target, newEntity -> {
                if (newEntity == entity) return;
                update.remove(newEntity);
            });
        }
    }

    /**
     * Reports that an untracked entity was moved, at most once per
     * {@link #UNREGISTERED_MOVE_REPORT_INTERVAL}.
     * <p>
     * An untracked entity is moved once per movement packet its client sends, so a line per
     * attempt puts the size of the log under the client's control: a 15 minute soak against a
     * player the tracker had rejected wrote 662,803 lines (84 MiB). The pending count is carried
     * in the line that does get written, so the volume stays visible.
     */
    private void reportUnregisteredMove(int entityId) {
        unregisteredMoveAttempts.incrementAndGet();
        final long now = System.nanoTime();
        final long last = lastUnregisteredMoveReport.get();
        if (now - last < UNREGISTERED_MOVE_REPORT_INTERVAL) return;
        if (!lastUnregisteredMoveReport.compareAndSet(last, now)) return;
        LOGGER.warn("Attempted to move unregistered entity {} in the entity tracker ({} attempts since the last report)",
                entityId, unregisteredMoveAttempts.getAndSet(0));
    }

    @Override
    public @Nullable Entity getEntityById(int id) {
        EntityTrackerEntry entry = entriesByEntityId.get(id);
        return entry == null ? null : entry.getEntity();
    }

    @Override
    public @Nullable Entity getEntityByUuid(UUID uuid) {
        EntityTrackerEntry entry = entriesByEntityUuid.get(uuid);
        return entry == null ? null : entry.getEntity();
    }

    @Override
    public <T extends Entity> void move(Entity entity, Point newPoint,
                                        Target<T> target, @Nullable Update<T> update) {
        EntityTrackerEntry entry = entriesByEntityId.get(entity.getEntityId());
        if (entry == null) {
            reportUnregisteredMove(entity.getEntityId());
            return;
        }
        Point oldPoint = entry.getLastPosition();
        entry.setLastPosition(newPoint);
        if (oldPoint.sameChunk(newPoint)) return;
        final long oldIndex = CoordConversion.chunkIndex(oldPoint);
        final long newIndex = CoordConversion.chunkIndex(newPoint);
        for (TargetEntry<Entity> targetEntry : targetEntries) {
            if (targetEntry.target.type().isInstance(entity)) {
                targetEntry.addToChunk(newIndex, entity);
                targetEntry.removeFromChunk(oldIndex, entity);
            }
        }
        if (update != null) {
            difference(oldPoint, newPoint, target, new Update<>() {
                @Override
                public void add(T added) {
                    if (entity != added) update.add(added);
                }

                @Override
                public void remove(T removed) {
                    if (entity != removed) update.remove(removed);
                }
            });
            update.referenceUpdate(newPoint, this);
        }
    }

    @Override
    public @Unmodifiable <T extends Entity> List<T> chunkEntities(int chunkX, int chunkZ, Target<T> target) {
        final TargetEntry<Entity> entry = targetEntries[target.ordinal()];
        @SuppressWarnings("unchecked")
        var chunkEntities = (List<T>) entry.chunkEntities.get(CoordConversion.chunkIndex(chunkX, chunkZ));
        if (chunkEntities == null) return List.of();
        return Collections.unmodifiableList(chunkEntities);
    }

    @Override
    public <T extends Entity> void nearbyEntitiesByChunkRange(Point point, int chunkRange, Target<T> target, Consumer<T> query) {
        final Long2ObjectSyncMap<List<Entity>> entities = targetEntries[target.ordinal()].chunkEntities;
        ChunkRange.chunksInRange(point, chunkRange, (chunkX, chunkZ) -> {
            @SuppressWarnings("unchecked") final var chunkEntities = (List<T>) entities.get(CoordConversion.chunkIndex(chunkX, chunkZ));
            if (chunkEntities == null || chunkEntities.isEmpty()) return;
            chunkEntities.forEach(query);
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Entity> void nearbyEntities(Point point, double range, Target<T> target, Consumer<T> query) {
        final Long2ObjectSyncMap<List<Entity>> entities = targetEntries[target.ordinal()].chunkEntities;
        final int minChunkX = CoordConversion.globalToChunk(point.x() - range);
        final int minChunkZ = CoordConversion.globalToChunk(point.z() - range);
        final int maxChunkX = CoordConversion.globalToChunk(point.x() + range);
        final int maxChunkZ = CoordConversion.globalToChunk(point.z() + range);
        // A negative range past a chunk edge turns the box inside out: no chunk to go through
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) return;
        final double squaredRange = range * range;
        final Consumer<Entity> inRange = entity -> {
            final EntityTrackerEntry trackerEntry = entriesByEntityId.get(entity.getEntityId());
            if (trackerEntry != null && point.distanceSquared(trackerEntry.getLastPosition()) <= squaredRange) {
                query.accept((T) entity);
            }
        };
        final long boxChunks = ((long) maxChunkX - minChunkX + 1) * ((long) maxChunkZ - minChunkZ + 1);
        if (boxChunks > entities(target).size()) {
            // More chunks in range than entities of the target, hence than chunks holding one: going through
            // those is cheaper. It also takes ranges such as Double.MAX_VALUE, whose box has up to 2^56 chunks.
            // As in the chunk loop, an entity changing chunks meanwhile may be missed or seen twice.
            for (List<Entity> bucket : entities.values()) bucket.forEach(inRange);
            return;
        }
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                final List<Entity> bucket = entities.get(CoordConversion.chunkIndex(chunkX, chunkZ));
                if (bucket != null) bucket.forEach(inRange);
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public @UnmodifiableView <T extends Entity> Set<T> entities(Target<T> target) {
        return (Set<T>) targetEntries[target.ordinal()].entitiesView;
    }

    @Override
    public Viewable viewable(int chunkX, int chunkZ) {
        return chunkViews.computeIfAbsent(CoordConversion.chunkIndex(chunkX, chunkZ), _ -> new ChunkView(chunkX, chunkZ));
    }

    @Override
    public void unregisterViewable(Viewable viewable) {
        if (!(viewable instanceof ChunkView chunkView))
            throw new IllegalArgumentException("Viewable is not a chunk view");
        chunkViews.remove(CoordConversion.chunkIndex(chunkView.chunkX, chunkView.chunkZ), chunkView);
    }

    private static class EntityTrackerEntry {
        private final Entity entity;
        private Point lastPosition;

        private EntityTrackerEntry(Entity entity, Point lastPosition) {
            this.entity = entity;
            this.lastPosition = lastPosition;
        }

        Entity getEntity() {
            return entity;
        }

        Point getLastPosition() {
            return lastPosition;
        }

        void setLastPosition(Point lastPosition) {
            this.lastPosition = lastPosition;
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Entity> void difference(Point oldPoint, Point newPoint,
                                               Target<T> target, Update<T> update) {
        final TargetEntry<Entity> entry = targetEntries[target.ordinal()];
        ChunkRange.chunksInRangeDiffering(newPoint.chunkX(), newPoint.chunkZ(), oldPoint.chunkX(), oldPoint.chunkZ(),
                entityViewDistance, (chunkX, chunkZ) -> {
                    // Add
                    final List<Entity> entities = entry.chunkEntities.get(CoordConversion.chunkIndex(chunkX, chunkZ));
                    if (entities == null || entities.isEmpty()) return;
                    for (Entity entity : entities) update.add((T) entity);
                }, (chunkX, chunkZ) -> {
                    // Remove
                    final List<Entity> entities = entry.chunkEntities.get(CoordConversion.chunkIndex(chunkX, chunkZ));
                    if (entities == null || entities.isEmpty()) return;
                    for (Entity entity : entities) update.remove((T) entity);
                });
    }

    static final class TargetEntry<T extends Entity> {
        private final EntityTracker.Target<T> target;
        private final Set<T> entities = ConcurrentHashMap.newKeySet(); // Thread-safe since exposed
        private final Set<T> entitiesView = Collections.unmodifiableSet(entities);
        // Chunk index -> entities inside it
        final Long2ObjectSyncMap<List<T>> chunkEntities = Long2ObjectSyncMap.hashmap();

        TargetEntry(Target<T> target) {
            this.target = target;
        }

        void addToChunk(long index, T entity) {
            while (true) {
                List<T> entities = chunkEntities.computeIfAbsent(index, _ -> new CopyOnWriteArrayList<>());
                // The bucket monitor makes the "became empty" and "is being filled" decisions mutually
                // exclusive, so a concurrent removal cannot drop a bucket we just added an entity to.
                synchronized (entities) {
                    if (chunkEntities.get(index) != entities) continue; // Bucket got dropped, retry
                    entities.add(entity);
                    return;
                }
            }
        }

        void removeFromChunk(long index, T entity) {
            List<T> entities = chunkEntities.get(index);
            if (entities == null) return;
            synchronized (entities) {
                entities.remove(entity);
                if (entities.isEmpty()) chunkEntities.remove(index, entities);
            }
        }
    }

    private final class ChunkView implements Viewable {
        private final int chunkX, chunkZ;
        final Set<Player> set = new SetImpl();

        private ChunkView(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        @Override
        public boolean addViewer(Player player) {
            throw new UnsupportedOperationException("Chunk viewers follow Player#isChunkInRing and cannot be added or removed manually");
        }

        @Override
        public boolean removeViewer(Player player) {
            throw new UnsupportedOperationException("Chunk viewers follow Player#isChunkInRing and cannot be added or removed manually");
        }

        // Not a snapshot: every call on the set, size, isEmpty, contains and iteration alike, scans the players again
        @Override
        public Set<? extends Player> getViewers() {
            return set;
        }

        private List<Player> references() {
            final List<Player> viewers = new ArrayList<>();
            // The viewers are the players whose ring of chunks has this one (Player#isChunkInRing): each its own
            // radius, and its own center, which the chunk update limiter can hold a few chunks behind the position.
            // Measured from the position, or with one server-wide radius, part of the chunks a player was sent would
            // not have it as a viewer, and their block updates would never reach it.
            for (Player player : entities(Target.PLAYERS)) {
                if (player.isChunkInRing(chunkX, chunkZ)) viewers.add(player);
            }
            return viewers;
        }

        final class SetImpl extends AbstractSet<Player> {
            @Override
            public Iterator<Player> iterator() {
                return references().iterator();
            }

            @Override
            public int size() {
                return references().size();
            }

            @Override
            public void forEach(Consumer<? super Player> action) {
                references().forEach(action);
            }
        }
    }
}
