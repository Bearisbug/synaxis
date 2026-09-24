package io.github.bearisbug.synaxis.entity;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The default tracking range of each entity type in one server process ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-019}). An entity's own {@link Entity#setTrackingRange(double, double)} takes
 * precedence; a type without a default keeps the square of
 * {@link io.github.bearisbug.synaxis.ServerConfig#entityViewDistance()} chunks.
 * <p>
 * Thread-safe.
 */
public final class TrackingRanges {
    private final Map<EntityType, TrackingRange> defaults = new ConcurrentHashMap<>();
    // Set once anything in the process has a range: until then the players skip the range pass
    private volatile boolean inUse;

    /**
     * Sets the default tracking range of an entity type.
     *
     * @param type       the entity type
     * @param horizontal the horizontal distance in blocks, positive
     * @param vertical   the vertical distance in blocks, positive
     * @throws IllegalArgumentException if a distance is not positive
     */
    public void setDefault(EntityType type, double horizontal, double vertical) {
        defaults.put(type, new TrackingRange(horizontal, vertical));
        inUse = true;
    }

    /**
     * Gets the default tracking range of an entity type.
     *
     * @param type the entity type
     * @return the default range, null if the type keeps the square of the entity view distance
     */
    public @Nullable TrackingRange getDefault(EntityType type) {
        return defaults.get(type);
    }

    void markInUse() {
        inUse = true;
    }

    boolean inUse() {
        return inUse;
    }
}
