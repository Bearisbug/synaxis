package io.github.bearisbug.synaxis.entity;

import it.unimi.dsi.fastutil.objects.Object2BooleanMaps;
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.utils.MathUtils;
import io.github.bearisbug.synaxis.utils.Range;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

// TODO

/**
 * Represents a query which can be call to find one or multiple entities.
 * It is based on the target selectors used in commands.
 */
public class EntityFinder {
    private TargetSelector targetSelector;

    private EntitySort entitySort;

    // Position
    private Point startPosition;
    private Float dx, dy, dz;
    private Range.Double distance;

    // By traits
    private Integer limit;
    private final ToggleableMap<EntityType> entityTypes = new ToggleableMap<>();
    private String constantName;
    private UUID constantUuid;
    private final ToggleableMap<String> names = new ToggleableMap<>();
    private final ToggleableMap<UUID> uuids = new ToggleableMap<>();


    // Players specific
    private final ToggleableMap<GameMode> gameModes = new ToggleableMap<>();
    private Range.Int level;

    public EntityFinder setTargetSelector(TargetSelector targetSelector) {
        this.targetSelector = targetSelector;
        return this;
    }

    public EntityFinder setEntitySort(EntitySort entitySort) {
        this.entitySort = entitySort;
        return this;
    }

    public EntityFinder setStartPosition(Point startPosition) {
        this.startPosition = startPosition;
        return this;
    }

    public EntityFinder setDistance(Range.Double distance) {
        this.distance = distance;
        return this;
    }

    public EntityFinder setLimit(int limit) {
        this.limit = limit;
        return this;
    }

    public EntityFinder setLevel(Range.Int level) {
        this.level = level;
        return this;
    }

    public EntityFinder setEntity(EntityType entityType, ToggleableType toggleableType) {
        this.entityTypes.put(entityType, toggleableType.getValue());
        return this;
    }

    public EntityFinder setConstantName(String constantName) {
        this.constantName = constantName;
        return this;
    }

    public EntityFinder setConstantUuid(UUID constantUuid) {
        this.constantUuid = constantUuid;
        return this;
    }

    public EntityFinder setName(String name, ToggleableType toggleableType) {
        this.names.put(name, toggleableType.getValue());
        return this;
    }

    public EntityFinder setUuid(UUID uuid, ToggleableType toggleableType) {
        this.uuids.put(uuid, toggleableType.getValue());
        return this;
    }

    public EntityFinder setGameMode(GameMode gameMode, ToggleableType toggleableType) {
        this.gameModes.put(gameMode, toggleableType.getValue());
        return this;
    }

    /**
     * Keeps only the entities in the volume from the start position to the start position plus the offsets,
     * like the {@code dx}, {@code dy} and {@code dz} arguments of a target selector. A negative offset extends
     * the volume towards the negative side.
     *
     * @param dx the offset on the X axis
     * @param dy the offset on the Y axis
     * @param dz the offset on the Z axis
     * @return this finder
     */
    public EntityFinder setDifference(float dx, float dy, float dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        return this;
    }

    /**
     * Find a list of entities (could be empty) based on the conditions
     *
     * @param instance the instance to search from,
     *                 null if the query can be executed using global data (all online players)
     * @param self     the source of the query, null if not any
     * @return all entities validating the conditions, can be empty
     */
    public List<Entity> find(ServerProcess process, @Nullable Instance instance, @Nullable Entity self) {
        if (targetSelector == TargetSelector.ENGINE_USERNAME) {
            Objects.requireNonNull(constantName, "The player name should not be null when searching for it");
            final Player player = process.connection().getOnlinePlayerByUsername(constantName);
            return player != null ? List.of(player) : List.of();
        } else if (targetSelector == TargetSelector.ENGINE_UUID) {
            Objects.requireNonNull(constantUuid, "The UUID should not be null when searching for it");
            Objects.requireNonNull(instance, "The instance should not be null when searching by UUID");
            final Entity entity = instance.getEntityByUuid(constantUuid);
            return entity != null ? List.of(entity) : List.of();
        }

        final Point pos = startPosition != null ? startPosition : (self != null ? self.getPosition() : Vec.ZERO);

        List<Entity> result = findTarget(process, instance, targetSelector, self);
        // Fast exit if there is nothing to process
        if (result.isEmpty())
            return result;

        // Distance argument
        if (distance != null) {
            final double minDistanceSquared = MathUtils.square(Math.max(distance.min() == null ? 0.0 : distance.min(), 0));
            final double maxDistanceSquared = MathUtils.square(distance.max() == null ? Double.MAX_VALUE : distance.max());

            result = result.stream()
                    .filter(entity -> MathUtils.isBetween(entity.getDistanceSquared(pos), minDistanceSquared, maxDistanceSquared))
                    .toList();
        }

        // Diff X/Y/Z
        if (dx != null || dy != null || dz != null) {
            result = result.stream().filter(entity -> {
                final var entityPosition = entity.getPosition();
                if (dx != null && !MathUtils.isBetweenUnordered(
                        entityPosition.x(),
                        pos.x(), pos.x() + dx))
                    return false;

                if (dy != null && !MathUtils.isBetweenUnordered(
                        entityPosition.y(),
                        pos.y(), pos.y() + dy))
                    return false;

                if (dz != null && !MathUtils.isBetweenUnordered(
                        entityPosition.z(),
                        pos.z(), pos.z() + dz))
                    return false;

                return true;
            }).toList();
        }

        // Entity type
        if (!entityTypes.isEmpty()) {
            result = result.stream()
                    .filter(entity -> filterToggleableMap(entity.getEntityType(), entityTypes))
                    .toList();
        }

        // GameMode
        if (!gameModes.isEmpty()) {
            result = result.stream()
                    .filter(Player.class::isInstance)
                    .filter(entity -> filterToggleableMap(((Player) entity).getGameMode(), gameModes))
                    .toList();
        }

        // Level
        if (level != null) {
            final int minLevel = level.min() == null ? 0 : level.min();
            final int maxLevel = level.max() == null ? Integer.MAX_VALUE : level.max();
            result = result.stream()
                    .filter(Player.class::isInstance)
                    .filter(entity -> MathUtils.isBetween(((Player) entity).getLevel(), minLevel, maxLevel))
                    .toList();
        }

        // Name
        if (!names.isEmpty()) {
            result = result.stream()
                    .filter(Player.class::isInstance)
                    .filter(entity -> filterToggleableMap(((Player) entity).getUsername(), names))
                    .toList();
        }

        // UUID
        if (!uuids.isEmpty()) {
            result = result.stream()
                    .filter(entity -> filterToggleableMap(entity.getUuid(), uuids))
                    .toList();
        }


        // @p/@r/@n are shorthands for a default sort and limit, both overridable
        EntitySort effectiveSort = entitySort;
        Integer effectiveLimit = limit;

        if (targetSelector == TargetSelector.NEAREST_PLAYER || targetSelector == TargetSelector.NEAREST_ENTITY) {
            if (effectiveSort == null) effectiveSort = EntitySort.NEAREST;
            if (effectiveLimit == null) effectiveLimit = 1;
        } else if (targetSelector == TargetSelector.RANDOM_PLAYER) {
            if (effectiveSort == null) effectiveSort = EntitySort.RANDOM;
            if (effectiveLimit == null) effectiveLimit = 1;
        }

        if (effectiveSort == null) {
            effectiveSort = EntitySort.ARBITRARY;
        }

        // Sort & limit
        final Comparator<Entity> nearestFirst = Comparator.comparingDouble(entity -> entity.getDistanceSquared(pos));

        if (effectiveLimit != null && effectiveLimit == 1) {
            result = switch (effectiveSort) {
                case ARBITRARY -> List.copyOf(result.subList(0, Math.min(1, result.size())));
                case RANDOM -> result.isEmpty() ? List.of()
                        : List.of(result.get(ThreadLocalRandom.current().nextInt(result.size())));
                case NEAREST -> result.stream().min(nearestFirst).map(List::<Entity>of).orElse(List.of());
                case FURTHEST -> result.stream().max(nearestFirst).map(List::<Entity>of).orElse(List.of());
            };
        } else if (effectiveSort == EntitySort.ARBITRARY) {
            if (effectiveLimit != null && result.size() > effectiveLimit) {
                result = List.copyOf(result.subList(0, effectiveLimit));
            }
        } else {
            final List<Entity> sorted = new ArrayList<>(result);

            if (effectiveSort == EntitySort.RANDOM) {
                Collections.shuffle(sorted);
            } else {
                // Each distance read once: entities move on their own threads, a comparator reading their position
                // again could see it change mid sort and break the sort contract
                record Ranked(Entity entity, double distanceSquared) {}
                final List<Ranked> ranked = new ArrayList<>(sorted.size());
                for (Entity entity : sorted) ranked.add(new Ranked(entity, entity.getDistanceSquared(pos)));
                final Comparator<Ranked> byDistance = Comparator.comparingDouble(Ranked::distanceSquared);
                ranked.sort(effectiveSort == EntitySort.NEAREST ? byDistance : byDistance.reversed());
                sorted.clear();
                for (Ranked entry : ranked) sorted.add(entry.entity());
            }

            result = effectiveLimit != null && sorted.size() > effectiveLimit
                    ? List.copyOf(sorted.subList(0, effectiveLimit))
                    : sorted;
        }

        return result;
    }

    public List<Entity> find(CommandSender sender) {
        return sender instanceof Player player ?
                find(sender.process(), player.getInstance(), player) : find(sender.process(), null, null);
    }

    /**
     * Shortcut of {@link #find(ServerProcess, Instance, Entity)} to retrieve the first
     * player element in the list.
     *
     * @return the first player returned by {@link #find(ServerProcess, Instance, Entity)}
     * @see #find(ServerProcess, Instance, Entity)
     */
    public @Nullable Player findFirstPlayer(ServerProcess process, @Nullable Instance instance, @Nullable Entity self) {
        final List<Entity> entities = find(process, instance, self);
        for (Entity entity : entities) {
            if (entity instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    public @Nullable Player findFirstPlayer(CommandSender sender) {
        return sender instanceof Player player ?
                findFirstPlayer(sender.process(), player.getInstance(), player) :
                findFirstPlayer(sender.process(), null, null);
    }

    public @Nullable Entity findFirstEntity(ServerProcess process, @Nullable Instance instance, @Nullable Entity self) {
        final List<Entity> entities = find(process, instance, self);
        return entities.isEmpty() ? null : entities.getFirst();
    }

    public @Nullable Entity findFirstEntity(CommandSender sender) {
        return sender instanceof Player player ?
                findFirstEntity(sender.process(), player.getInstance(), player) :
                findFirstEntity(sender.process(), null, null);
    }

    public enum TargetSelector {
        NEAREST_PLAYER, RANDOM_PLAYER, ALL_PLAYERS, ALL_ENTITIES, SELF, ENGINE_USERNAME, ENGINE_UUID, NEAREST_ENTITY
    }

    public enum EntitySort {
        ARBITRARY, FURTHEST, NEAREST, RANDOM
    }

    public enum ToggleableType {
        INCLUDE(true), EXCLUDE(false);

        private final boolean value;

        ToggleableType(boolean value) {
            this.value = value;
        }

        public boolean getValue() {
            return value;
        }
    }

    @SuppressWarnings("serial") // never serialized
    private static class ToggleableMap<T> extends Object2BooleanOpenHashMap<T> {
    }

    private static List<Entity> findTarget(ServerProcess process, @Nullable Instance instance,
                                           TargetSelector targetSelector,
                                           @Nullable Entity self) {
        final var players = instance != null ? instance.getPlayers() : process.connection().getOnlinePlayers();
        if (targetSelector == TargetSelector.NEAREST_PLAYER || targetSelector == TargetSelector.RANDOM_PLAYER || targetSelector == TargetSelector.ALL_PLAYERS) {
            return List.copyOf(players);
        } else if (targetSelector == TargetSelector.NEAREST_ENTITY || targetSelector == TargetSelector.ALL_ENTITIES) {
            if (instance != null) {
                return List.copyOf(instance.getEntities());
            }
            // Get entities from every instance
            var instances = process.instance().getInstances();
            List<Entity> entities = new ArrayList<>();
            for (Instance inst : instances) {
                entities.addAll(inst.getEntities());
            }
            return entities;
        } else if (targetSelector == TargetSelector.SELF) {
            return self != null ? List.of(self) : List.of();
        }
        throw new IllegalStateException("Weird thing happened: " + targetSelector);
    }

    private static <T> boolean filterToggleableMap(T value, ToggleableMap<T> map) {
        for (var entry : Object2BooleanMaps.fastIterable(map)) {
            if (entry.getBooleanValue() != Objects.equals(value, entry.getKey())) {
                return false;
            }
        }
        return true;
    }
}
