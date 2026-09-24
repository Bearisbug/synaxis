package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.entity.EntityType;

/**
 * Entity visibility settings of the process ({@code docs/DESIGN-PROD.md} {@code API-PROD-019}).
 * <p>
 * What is set here is process wide and outlives the plugin that set it. A single entity overrides
 * its type with {@code Entity#setTrackingRange}, and leaves the per player visible entity limit with
 * {@code Entity#setAlwaysVisible}.
 */
public interface PluginEntities {

    /**
     * Sets how far away players see the entities of a type: within {@code horizontal} blocks on the
     * horizontal plane and {@code vertical} blocks up or down. The range narrows the square of
     * {@code synaxis.entity-view-distance} chunks around the player and never widens it. A type
     * nobody set keeps that square.
     *
     * @param category   the entity type, a category of the plugin's choosing
     * @param horizontal the horizontal distance in blocks, positive
     * @param vertical   the vertical distance in blocks, positive
     * @throws IllegalArgumentException if a distance is not positive
     */
    void setDefaultTrackingRange(EntityType category, double horizontal, double vertical);
}
