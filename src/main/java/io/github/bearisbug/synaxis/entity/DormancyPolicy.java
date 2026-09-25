package io.github.bearisbug.synaxis.entity;

/**
 * How often an entity runs its tick, by how far the nearest player of its instance is
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-019}). Set with {@link Entity#setDormancyPolicy}.
 * <p>
 * A tick the entity sits out skips its movement and physics, its block contacts,
 * {@link Entity#update(long)}, the {@link io.github.bearisbug.synaxis.event.entity.EntityTickEvent}
 * and its potion effects, and {@link Entity#getAliveTicks()} does not count it. Its scheduler still
 * runs every tick, so a task handed to the entity is not held back. While it runs less often than
 * every tick the entity sends no scheduled position synchronization; the first tick it runs every
 * tick again sends one. A move it makes on a tick it runs, by its velocity or by
 * {@link Entity#moveTo(io.github.bearisbug.synaxis.coordinate.Pos)}, still reaches its viewers as a
 * relative move, except for the types only ever synchronized, such as items and projectiles.
 * <p>
 * This is also the hook for the plugin's own AI: the engine calls it on the tick thread of the
 * entity, which the caller owns, on the first tick after the policy was set and then every 5 ticks,
 * so an AI that should slow down as well learns here how far the nearest player is.
 */
@FunctionalInterface
public interface DormancyPolicy {

    /**
     * How often the entity runs its tick with the nearest player this far away, such as
     * {@code (entity, distance) -> distance > 32 ? 20 : 1}.
     *
     * @param entity                the entity, owned by the calling thread
     * @param nearestPlayerDistance the distance in blocks from the entity to the nearest player of its
     *                              instance, as the players stood when the tick started;
     *                              {@link Double#POSITIVE_INFINITY} with no player in the instance
     * @return run the tick once every that many ticks, 1 or less to run every tick
     */
    int tickInterval(Entity entity, double nearestPlayerDistance);
}
