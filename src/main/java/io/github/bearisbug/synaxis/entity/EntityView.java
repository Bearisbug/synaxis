package io.github.bearisbug.synaxis.entity;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.EntityTracker;
import io.github.bearisbug.synaxis.instance.Instance;
import org.jetbrains.annotations.Nullable;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class EntityView {
    private final Entity entity;
    private final Set<Player> manualViewers = new HashSet<>();

    // Decide if this entity should be viewable to X players
    public final Option<Player> viewableOption;
    // Decide if this entity should view X entities
    public final Option<Entity> viewerOption;

    final Set<Player> set = new SetImpl();
    private final Object mutex = this;

    private volatile @Nullable TrackedLocation trackedLocation;

    /** How often a player applies the tracking ranges and the visible entity limit, see {@link #refreshVisibility}. */
    static final int REFRESH_INTERVAL_TICKS = 5;
    // Players only, guarded by the mutex: the auto viewed entities counted towards the visible
    // entity limit, see #isCounted
    private final IntSet counted = new IntOpenHashSet();

    public EntityView(Entity entity) {
        this.entity = entity;
        this.viewableOption = new Option<>(EntityTracker.Target.PLAYERS, Entity::autoViewEntities,
                player -> showEntityToPlayer(this.entity, player),
                player -> hideEntityFromPlayer(this.entity, player)
        );
        this.viewerOption = new Option<>(EntityTracker.Target.ENTITIES, Entity::isAutoViewable,
                entity instanceof Player player ? e -> e.viewEngine.viewableOption.addition.accept(player) : null,
                entity instanceof Player player ? e -> e.viewEngine.viewableOption.removal.accept(player) : null);
    }

    private static void showEntityToPlayer(Entity entity, Player player) {
        // Collects the chain of entities, including the vehicle and all passengers, that should be visible to the player.
        List<Entity> visibleChain = new ArrayList<>();
        collectEntityChain(entity, player, visibleChain);

        if (visibleChain.isEmpty()) return;

        // Send spawn packets
        for (Entity e : visibleChain) {
            e.updateNewViewer(player);
        }

        // Send passenger packets (in reverse order)
        for (int i = visibleChain.size() - 1; i >= 0; i--) {
            Entity e = visibleChain.get(i);
            if (e.hasPassenger() && e.getPassengers().stream().anyMatch(visibleChain::contains)) {
                player.sendPacket(e.getPassengersPacket());
            }
        }
    }

    private static void collectEntityChain(Entity entity, Player player, List<Entity> chain) {
        var lock1 = player.getEntityId() < entity.getEntityId() ? player : entity;
        var lock2 = lock1 == entity ? player : entity;
        boolean shouldAdd = false;
        synchronized (lock1.viewEngine.mutex) {
            synchronized (lock2.viewEngine.mutex) {
                if (!entity.isViewer(player) &&
                        player.getVehicle() != entity &&
                        entity.viewEngine.viewableOption.predicate(player) &&
                        player.viewEngine.viewerOption.predicate(entity) &&
                        player.viewEngine.admits(entity, player)) {

                    entity.viewEngine.viewableOption.register(player);
                    player.viewEngine.viewerOption.register(entity);
                    if (isCounted(entity)) player.viewEngine.counted.add(entity.getEntityId());
                    shouldAdd = true;
                }
            }
        }
        if (shouldAdd) {
            chain.add(entity);
            for (Entity passenger : entity.getPassengers()) {
                collectEntityChain(passenger, player, chain);
            }
        }
    }

    private static void hideEntityFromPlayer(Entity entity, Player player) {
        var lock1 = player.getEntityId() < entity.getEntityId() ? player : entity;
        var lock2 = lock1 == entity ? player : entity;
        synchronized (lock1.viewEngine.mutex) {
            synchronized (lock2.viewEngine.mutex) {
                entity.viewEngine.viewableOption.unregister(player);
                player.viewEngine.viewerOption.unregister(entity);
                player.viewEngine.counted.remove(entity.getEntityId());
            }
        }
        entity.updateOldViewer(player);
        final List<Entity> passengers = entity.getPassengers();
        if (!passengers.isEmpty()) {
            for (Entity passenger : passengers) {
                if (passenger != player) hideEntityFromPlayer(passenger, player);
            }
        }
    }

    /**
     * Whether {@code player} may take {@code entity} in on top of what it already sees. Called with
     * both view mutexes held, this being the player's view.
     */
    private boolean admits(Entity entity, Player player) {
        // A passenger comes and goes with its vehicle
        if (entity.getVehicle() != null) return true;
        if (!withinRange(entity, player)) return false;
        if (entity.isAlwaysVisible()) return true;
        final int limit = player.process().config().maxVisibleEntitiesPerPlayer();
        return limit == 0 || counted.size() < limit;
    }

    /** Whether {@code entity} counts towards the visible entity limit of the players seeing it. */
    private static boolean isCounted(Entity entity) {
        return entity.getVehicle() == null && !entity.isAlwaysVisible();
    }

    private static boolean withinRange(Entity entity, Player player) {
        TrackingRange range = entity.getTrackingRange();
        if (range == null) {
            final ServerProcess process = entity.process();
            if (process == null) return true;
            range = process.trackingRanges().getDefault(entity.getEntityType());
            if (range == null) return true;
        }
        final Pos at = entity.getPosition();
        final Pos from = player.getPosition();
        return range.contains(at.x() - from.x(), at.y() - from.y(), at.z() - from.z());
    }

    /**
     * Applies the tracking ranges and the visible entity limit ({@code docs/DESIGN-PROD.md}
     * {@code API-PROD-019}) to what this player sees, every {@value #REFRESH_INTERVAL_TICKS} ticks
     * of its own, on its tick thread.
     * <p>
     * The tracker only reports an entity when it crosses a chunk border, and {@link #admits} turns
     * away whatever does not fit at that moment: this pass drops what went out of range or was
     * pushed out by nearer ones, and takes in what came into range or now fits. The players are
     * spread over the interval by entity id, so that the passes do not all land on the same tick.
     * A process with neither a range nor a limit skips it.
     *
     * @param aliveTicks the ticks this player has been alive for
     */
    void refreshVisibility(long aliveTicks) {
        if ((aliveTicks + entity.getEntityId()) % REFRESH_INTERVAL_TICKS != 0) return;
        if (!(entity instanceof Player player) || !viewerOption.isAuto()) return;
        final TrackedLocation trackedLocation = this.trackedLocation;
        if (trackedLocation == null) return;
        final ServerProcess process = trackedLocation.instance().process();
        final int limit = process.config().maxVisibleEntitiesPerPlayer();
        if (limit == 0 && !process.trackingRanges().inUse()) return;

        final Pos origin = player.getPosition();
        final List<Entity> candidates = viewerOption.select(Entity::isAutoViewable);
        final List<Entity> always = new ArrayList<>();
        final List<Nearby> nearby = new ArrayList<>();
        for (Entity candidate : candidates) {
            if (!candidate.viewEngine.viewableOption.predicate(player) || !viewerOption.predicate(candidate)) continue;
            // The player's own vehicle is never taken away from it
            if (candidate != player.getVehicle() && !withinRange(candidate, player)) continue;
            if (candidate.isAlwaysVisible() || candidate == player.getVehicle()) always.add(candidate);
            else nearby.add(new Nearby(candidate, candidate.getPosition().distanceSquared(origin)));
        }
        if (limit > 0 && nearby.size() > limit) {
            nearby.sort(Comparator.comparingDouble(Nearby::distanceSquared));
            nearby.subList(limit, nearby.size()).clear();
        }
        final IntSet wanted = new IntOpenHashSet(always.size() + nearby.size());
        for (Entity visible : always) wanted.add(visible.getEntityId());
        for (Nearby visible : nearby) wanted.add(visible.entity().getEntityId());

        final IntSet registered;
        synchronized (mutex) {
            registered = new IntOpenHashSet(viewerOption.bitSet);
        }
        // Out first, so that the ones kept and the nearer ones taken in fit under the limit
        for (Entity candidate : candidates) {
            final int id = candidate.getEntityId();
            if (registered.contains(id) && !wanted.contains(id)) hideEntityFromPlayer(candidate, player);
        }
        synchronized (mutex) {
            counted.clear();
            for (Nearby visible : nearby) {
                final int id = visible.entity().getEntityId();
                if (viewerOption.bitSet.contains(id)) counted.add(id);
            }
        }
        for (Entity visible : always) {
            if (!registered.contains(visible.getEntityId())) showEntityToPlayer(visible, player);
        }
        for (Nearby visible : nearby) {
            if (!registered.contains(visible.entity().getEntityId())) showEntityToPlayer(visible.entity(), player);
        }
    }

    private record Nearby(Entity entity, double distanceSquared) {
    }

    public void updateTracker(@Nullable Instance instance, Point point) {
        this.trackedLocation = instance != null ? new TrackedLocation(instance, point) : null;
    }

    record TrackedLocation(Instance instance, Point point) {
    }

    public boolean manualAdd(Player player) {
        if (player == this.entity) return false;
        synchronized (mutex) {
            if (manualViewers.add(player)) {
                viewableOption.bitSet.add(player.getEntityId());
                return true;
            }
            return false;
        }
    }

    public boolean manualRemove(Player player) {
        if (player == this.entity) return false;
        synchronized (mutex) {
            if (manualViewers.remove(player)) {
                viewableOption.bitSet.remove(player.getEntityId());
                return true;
            }
            return false;
        }
    }

    public void forManuals(Consumer<? super Player> consumer) {
        synchronized (mutex) {
            Set<Player> manualViewersCopy = Set.copyOf(this.manualViewers);
            manualViewersCopy.forEach(consumer);
        }
    }

    public boolean hasPredictableViewers() {
        // Verify if this entity's viewers can be predicted from surrounding entities
        synchronized (mutex) {
            return viewableOption.isAuto() && viewableOption.predicate == null && manualViewers.isEmpty();
        }
    }

    public void handleAutoViewAddition(Entity entity) {
        handleAutoView(entity, viewerOption.addition, viewableOption.addition);
    }

    public void handleAutoViewRemoval(Entity entity) {
        handleAutoView(entity, viewerOption.removal, viewableOption.removal);
    }

    private void handleAutoView(Entity entity, @Nullable Consumer<Entity> viewer, @Nullable Consumer<Player> viewable) {
        if (this.entity instanceof Player && viewerOption.isAuto() && entity.isAutoViewable()) {
            if (viewer != null) viewer.accept(entity); // Send packet to this player
        }
        if (entity instanceof Player player && player.autoViewEntities() && viewableOption.isAuto()) {
            if (viewable != null) viewable.accept(player); // Send packet to the range-visible player
        }
    }

    public final class Option<T extends Entity> {
        @SuppressWarnings("rawtypes")
        private static final AtomicIntegerFieldUpdater<EntityView.Option> UPDATER = AtomicIntegerFieldUpdater.newUpdater(EntityView.Option.class, "auto");
        // Entities that should be tracked from this option
        private final EntityTracker.Target<T> target;
        // The condition that must be met for this option to be considered auto.
        private final Predicate<T> loopPredicate;
        // The consumers to be called when an entity is added/removed.
        public final @Nullable Consumer<T> addition, removal;
        // Contains all the auto-entity ids that are viewable by this option.
        public final IntSet bitSet = new IntOpenHashSet();
        // 1 if auto, 0 if manual
        private volatile int auto = 1;
        // The custom rule used to determine if an entity is viewable.
        // null if auto-viewable
        private @Nullable Predicate<? super T> predicate = null;

        public Option(EntityTracker.Target<T> target, Predicate<T> loopPredicate,
                      @Nullable Consumer<T> addition, @Nullable Consumer<T> removal) {
            this.target = target;
            this.loopPredicate = loopPredicate;
            this.addition = addition;
            this.removal = removal;
        }

        public boolean isAuto() {
            return auto == 1;
        }

        public boolean predicate(T entity) {
            final Predicate<? super T> predicate = this.predicate;
            return predicate == null || predicate.test(entity);
        }

        public boolean isRegistered(T entity) {
            return bitSet.contains(entity.getEntityId());
        }

        public void register(T entity) {
            assert entity.getInstance() != null : "Instance-less entity shouldn't be registered as viewer";
            this.bitSet.add(entity.getEntityId());
        }

        public void unregister(T entity) {
            this.bitSet.remove(entity.getEntityId());
        }

        public void updateAuto(boolean autoViewable) {
            final boolean previous = UPDATER.getAndSet(this, autoViewable ? 1 : 0) == 1;
            if (previous != autoViewable) {
                final Consumer<T> action = autoViewable ? addition : removal;
                // Non-players have no viewer consumers, there is nothing to send
                if (action == null) return;
                select(autoViewable ? loopPredicate : this::isRegistered).forEach(action);
            }
        }

        public void updateRule(@Nullable Predicate<? super T> predicate) {
            synchronized (mutex) {
                this.predicate = predicate;
            }
            updateRule0(predicate);
        }

        public void updateRule() {
            final Predicate<? super T> predicate;
            synchronized (mutex) {
                predicate = this.predicate;
            }
            updateRule0(predicate);
        }

        void updateRule0(@Nullable Predicate<? super T> predicate) {
            final Consumer<T> addition = this.addition;
            final Consumer<T> removal = this.removal;
            // Non-players have no viewer consumers, there is nothing to send
            if (addition == null || removal == null) return;
            final List<T> additions = new ArrayList<>();
            final List<T> removals = new ArrayList<>();
            synchronized (mutex) {
                for (T entity : select(loopPredicate)) {
                    final boolean visible = predicate == null || predicate.test(entity);
                    if (visible != isRegistered(entity)) (visible ? additions : removals).add(entity);
                }
            }
            additions.forEach(addition);
            removals.forEach(removal);
        }

        /**
         * Collects the entities an addition or removal applies to. The consumers are deliberately left
         * to the caller to run once this mutex is released: they take both view mutexes ordered by
         * entity id, and entering that region while already holding one of them reverses the order
         * against every caller coming from the tracker.
         */
        private List<T> select(Predicate<? super T> visibilityPredicate) {
            final List<T> selected = new ArrayList<>();
            synchronized (mutex) {
                references().forEach(entity -> {
                    if (entity == EntityView.this.entity || !visibilityPredicate.test(entity)) return;
                    if (entity instanceof Player player && manualViewers.contains(player)) return;
                    if (entity.getVehicle() != null) return;
                    selected.add(entity);
                });
            }
            return selected;
        }

        private int lastSize;

        private Collection<T> references() {
            final TrackedLocation trackedLocation = EntityView.this.trackedLocation;
            if (trackedLocation == null) return List.of();
            final Instance instance = trackedLocation.instance();
            final Point point = trackedLocation.point();

            Int2ObjectOpenHashMap<T> entityMap = new Int2ObjectOpenHashMap<>(lastSize);
            instance.getEntityTracker().nearbyEntitiesByChunkRange(point, instance.process().config().entityViewDistance(), target,
                    (entity) -> entityMap.putIfAbsent(entity.getEntityId(), entity));
            this.lastSize = entityMap.size();
            return entityMap.values();
        }
    }

    final class SetImpl extends AbstractSet<Player> {
        @Override
        public Iterator<Player> iterator() {
            List<Player> players;
            synchronized (mutex) {
                var bitSet = viewableOption.bitSet;
                if (bitSet.isEmpty()) return Collections.emptyIterator();
                Instance instance = entity.getInstance();
                if (instance == null) return Collections.emptyIterator();
                players = new ArrayList<>(bitSet.size());
                for (IntIterator it = bitSet.intIterator(); it.hasNext(); ) {
                    final int id = it.nextInt();
                    final Player player = (Player) instance.getEntityById(id);
                    if (player != null) players.add(player);
                }
            }
            return players.iterator();
        }

        @Override
        public int size() {
            synchronized (mutex) {
                Instance instance = entity.getInstance();
                if (instance == null) return 0;
                int count = 0;
                for (IntIterator it = viewableOption.bitSet.intIterator(); it.hasNext(); ) {
                    if (instance.getEntityById(it.nextInt()) != null) count++;
                }
                return count;
            }
        }

        @Override
        public boolean isEmpty() {
            synchronized (mutex) {
                return viewableOption.bitSet.isEmpty();
            }
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof Player player)) return false;
            synchronized (mutex) {
                return viewableOption.isRegistered(player);
            }
        }
    }
}