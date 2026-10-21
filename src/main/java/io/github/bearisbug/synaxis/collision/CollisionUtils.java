package io.github.bearisbug.synaxis.collision;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.WorldBorder;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.ChunkCache;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

@ApiStatus.Internal
public final class CollisionUtils {

    /**
     * Checks for entity collisions
     * <p>
     * The results are sorted by {@link EntityCollisionResult#percentage()}, the entities the box already
     * overlaps at {@code pos} first (a percentage of 0).
     *
     * @param velocity     the velocity of the entity
     * @param extendRadius the largest entity bounding box we can collide with
     *                     Measured from bottom center to top corner
     *                     This is used to extend the search radius for entities we collide with
     *                     For players this is sqrt(0.3^2 + 0.3^2 + 1.8^2) ~= 1.85
     */
    public static List<EntityCollisionResult> checkEntityCollisions(Instance instance, BoundingBox boundingBox, Point pos, Vec velocity, double extendRadius, Function<Entity, Boolean> entityFilter, @Nullable PhysicsResult physicsResult) {
        return EntityCollision.checkCollision(instance.getEntityTracker(), boundingBox, pos, velocity, extendRadius, entityFilter, physicsResult);
    }

    /**
     * Checks for entity collisions
     * <p>
     * The results never include {@code entity} itself. They are sorted by {@link EntityCollisionResult#percentage()},
     * the entities its box already overlaps first (a percentage of 0).
     *
     * @param entity        the entity to check collisions for
     * @param velocity      the velocity of the entity
     * @param extendRadius  the largest entity bounding box we can collide with
     *                      Measured from bottom center to top corner
     *                      This is used to extend the search radius for entities we collide with
     *                      For players this is sqrt(0.3^2 + 0.3^2 + 1.8^2) ~= 1.85
     * @param entityFilter  the entity filter
     * @param physicsResult optional physics result
     * @return the entity collision results
     * @throws IllegalStateException if the entity is not in an instance
     */
    public static List<EntityCollisionResult> checkEntityCollisions(Entity entity, Vec velocity, double extendRadius, Function<Entity, Boolean> entityFilter, @Nullable PhysicsResult physicsResult) {
        final Instance instance = entity.getInstance();
        Check.stateCondition(instance == null, "entity {0} is not in an instance", entity);
        return EntityCollision.checkCollision(instance.getEntityTracker(), entity.getBoundingBox(), entity.getPosition(), velocity, extendRadius,
                e -> e != entity && entityFilter.apply(e), physicsResult);
    }

    /**
     * Moves an entity with physics applied (ie checking against blocks)
     * <p>
     * Works by getting all the full blocks that an entity could interact with.
     * All bounding boxes inside the full blocks are checked for collisions with the entity.
     * Blocks in unloaded chunks count as {@link Block#STONE}, so the movement stops at the edge of the loaded area.
     * <p>
     * The world border is not collided with. Entity movement goes through
     * {@link PhysicsUtils#simulateMovement}, which collides with the border of the instance.
     *
     * @param entity            the entity to move
     * @param entityVelocity    the velocity of the entity
     * @param lastPhysicsResult the last physics result, can be null
     * @return the result of physics simulation
     * @throws IllegalStateException if the entity is not in an instance
     */
    public static PhysicsResult handlePhysics(Entity entity, Vec entityVelocity,
                                              @Nullable PhysicsResult lastPhysicsResult) {
        final Instance instance = entity.getInstance();
        Check.stateCondition(instance == null, "entity {0} is not in an instance", entity);
        return handlePhysics(instance, entity.getChunk(),
                entity.getBoundingBox(),
                entity.getPosition(), entityVelocity,
                lastPhysicsResult, false);
    }

    /**
     * Moves bounding box with physics applied (ie checking against blocks)
     * <p>
     * Works by getting all the full blocks that a bounding box could interact with.
     * All bounding boxes inside the full blocks are checked for collisions with the given bounding box.
     * Blocks in unloaded chunks count as {@link Block#STONE}, so the movement stops at the edge of the loaded area.
     *
     * @param boundingBox the bounding box to move
     * @return the result of physics simulation
     */
    public static PhysicsResult handlePhysics(Instance instance, @Nullable Chunk chunk,
                                              BoundingBox boundingBox,
                                              Pos position, Vec velocity,
                                              @Nullable PhysicsResult lastPhysicsResult, boolean singleCollision) {
        final Block.Getter getter = new ChunkCache(instance, chunk != null ? chunk : instance.getChunkAt(position), Block.STONE);
        return handlePhysics(getter, boundingBox, position, velocity, lastPhysicsResult, singleCollision);
    }

    /**
     * Moves bounding box with physics applied (ie checking against blocks)
     * <p>
     * Works by getting all the full blocks that a bounding box could interact with.
     * All bounding boxes inside the full blocks are checked for collisions with the given bounding box.
     *
     * @param blockGetter the block getter to check collisions against, ensure block access is synchronized
     * @return the result of physics simulation
     */
    @ApiStatus.Internal
    public static PhysicsResult handlePhysics(Block.Getter blockGetter,
                                              BoundingBox boundingBox,
                                              Pos position, Vec velocity,
                                              @Nullable PhysicsResult lastPhysicsResult, boolean singleCollision) {
        return BlockCollision.handlePhysics(boundingBox,
                velocity, position,
                blockGetter, lastPhysicsResult, singleCollision, null);
    }

    /**
     * Moves a bounding box with physics applied, colliding with blocks and the world border.
     * <p>
     * The world border collides as four vertical walls at the block-aligned border bounds.
     * A box whose movement target stays inside the walls never collides with them, and a box
     * already extending past a wall keeps moving away from the border freely.
     *
     * @param blockGetter the block getter to check collisions against, ensure block access is synchronized
     * @param worldBorder the world border to collide with
     * @return the result of physics simulation
     */
    @ApiStatus.Internal
    public static PhysicsResult handlePhysics(Block.Getter blockGetter, WorldBorder worldBorder,
                                              BoundingBox boundingBox,
                                              Pos position, Vec velocity,
                                              @Nullable PhysicsResult lastPhysicsResult, boolean singleCollision) {
        return BlockCollision.handlePhysics(boundingBox,
                velocity, position,
                blockGetter, lastPhysicsResult, singleCollision, worldBorder);
    }

    /**
     * Checks whether shape is reachable by the given line of sight
     * (ie there are no blocks colliding with it). The line stops at the first block it hits.
     *
     * @param instance the instance.
     * @param chunk    optional chunk reference for speedup purposes.
     * @param start    start of the line of sight.
     * @param end      end of the line of sight.
     * @param shape    shape to check.
     * @param shapePos position of the shape to check.
     * @return true is shape is reachable by the given line of sight; false otherwise.
     */
    public static boolean isLineOfSightReachingShape(Instance instance, @Nullable Chunk chunk,
                                                     Point start, Point end,
                                                     Shape shape, Point shapePos) {
        final PhysicsResult result = handlePhysics(instance, chunk,
                BoundingBox.ZERO, start.asPos(), end.sub(start).asVec(),
                null, true);

        return shape.intersectBox(shapePos.sub(result.newPosition()).sub(Vec.EPSILON), BoundingBox.ZERO);
    }

    /**
     * Moves an entity with physics applied (ie checking against blocks), without a previous physics result.
     * Blocks in unloaded chunks count as {@link Block#STONE}, so the movement stops at the edge of the loaded area.
     * <p>
     * The world border is not collided with. Entity movement goes through
     * {@link PhysicsUtils#simulateMovement}, which collides with the border of the instance.
     *
     * @param entity         the entity to move
     * @param entityVelocity the velocity of the entity
     * @return the result of physics simulation
     * @throws IllegalStateException if the entity is not in an instance
     */
    public static PhysicsResult handlePhysics(Entity entity, Vec entityVelocity) {
        return handlePhysics(entity, entityVelocity, null);
    }

    /**
     * Gets an entity a block placed at a position would intersect, if any.
     * <p>
     * Only entities that prevent block placement ({@link Entity#preventBlockPlacement()}) are considered, and only
     * those whose bounding box reaches at most 16 blocks from their feet: a box set with
     * {@link Entity#setBoundingBox(BoundingBox)} that reaches farther is not checked.
     *
     * @param instance the instance
     * @param blockPos the position of the block
     * @param b        the block to place
     * @return an entity the block would intersect, null if none
     */
    public static @Nullable Entity canPlaceBlockAt(Instance instance, Point blockPos, Block b) {
        return BlockCollision.canPlaceBlockAt(instance, blockPos, b);
    }

    @ApiStatus.Internal
    public static Shape parseCollisionShape(Map<Object, Object> internCache, String shape) {
        final Shape cachedShape = (Shape) internCache.get(shape);
        if (cachedShape != null) return cachedShape;
        final Shape parsedShape = ShapeImpl.parseShapeFromRegistry(shape, (byte) 0);
        // Cache the interned instance, not the parsed one, otherwise this string keeps returning a
        // duplicate of a shape that is already interned under a differently spelled string
        final Shape internedShape = (Shape) internCache.computeIfAbsent(parsedShape, _ -> parsedShape);
        internCache.put(shape, internedShape);
        return internedShape;
    }

    @ApiStatus.Internal
    public static Shape parseOcclusionShape(Map<Object, Object> internCache, String shape, boolean occludes, byte lightEmission) {
        record ShapeEntry(String shape, boolean occludes, byte lightEmission) {} // Easy way to Hashcode
        ShapeEntry entry = new ShapeEntry(shape, occludes, lightEmission);
        final Shape cachedShape = (Shape) internCache.get(entry);
        if (cachedShape != null) return cachedShape;
        final Shape parsedShape = occludes ? ShapeImpl.parseShapeFromRegistry(shape, lightEmission) : ShapeImpl.emptyShape(lightEmission);
        final Shape internedShape = (Shape) internCache.computeIfAbsent(parsedShape, _ -> parsedShape);
        internCache.put(entry, internedShape);
        return internedShape;
    }

    /**
     * Simulate the entity's collision physics as if the world had no blocks
     *
     * @param entityPosition the position of the entity
     * @param entityVelocity the velocity of the entity
     * @return the result of physics simulation
     */
    public static PhysicsResult blocklessCollision(Pos entityPosition, Vec entityVelocity) {
        return PhysicsResult.noCollision(entityPosition.add(entityVelocity), entityVelocity);
    }
}
