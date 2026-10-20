package io.github.bearisbug.synaxis.collision;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.coordinate.BlockFace;

public interface Shape {
    /**
     * Checks whether light leaving this shape through {@code face} is stopped by the faces between this shape and
     * {@code shape}, the shape on the other side of that face.
     * <p>
     * Only defined between two block shapes, as returned by
     * {@link io.github.bearisbug.synaxis.instance.block.Block#occlusionShape()} or
     * {@link io.github.bearisbug.synaxis.instance.block.Block#collisionShape()}. Passing a {@link BoundingBox} or
     * an {@link Entity} to a block shape throws a {@link ClassCastException}; their own implementations always
     * return false.
     *
     * @param shape the block shape on the other side of {@code face}
     * @param face  the face of this shape the light leaves through
     * @return true if the light is stopped
     */
    boolean isOccluded(Shape shape, BlockFace face);

    /**
     * Returns true if the given block face is completely covered by this shape, false otherwise.
     * @param face The face to test
     */
    default boolean isFaceFull(BlockFace face) {
        return false;
    }

    /**
     * Checks if two bounding boxes intersect.
     *
     * @param positionRelative Relative position of bounding box to check with
     * @param boundingBox      Bounding box to check for intersections with
     * @return is an intersection found
     */
    boolean intersectBox(Point positionRelative, BoundingBox boundingBox);

    /**
     * Checks if a moving bounding box will hit this shape.
     *
     * @param rayStart     Position of the moving shape
     * @param rayDirection Movement vector
     * @param shapePos     Position of this shape
     * @param moving       Bounding Box of moving shape
     * @param finalResult  Stores final SweepResult
     * @return is an intersection found
     */
    boolean intersectBoxSwept(Point rayStart, Point rayDirection,
                              Point shapePos, BoundingBox moving, SweepResult finalResult);


    /**
     * Used to know if this {@link BoundingBox} intersects with the bounding box of an entity.
     *
     * @param entity the entity to check the bounding box
     * @return true if this bounding box intersects with the entity, false otherwise
     */
    default boolean intersectEntity(Point src, Entity entity) {
        return intersectBox(src.sub(entity.getPosition()), entity.getBoundingBox());
    }

    /**
     * Relative Start
     *
     * @return Start of shape
     */
    Point relativeStart();

    /**
     * Relative End
     *
     * @return End of shape
     */
    Point relativeEnd();
}
