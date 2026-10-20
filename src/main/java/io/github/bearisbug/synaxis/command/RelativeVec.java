package io.github.bearisbug.synaxis.command;

import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Represents a location which can have fields relative to an {@link Entity} position.
 * <p>
 * Useful for parsing Vec2 or Vec3 types
 */
public record RelativeVec(Vec vec, CoordinateType coordinateType, boolean relativeX, boolean relativeY, boolean relativeZ) {

    public RelativeVec {
        Check.argCondition(relativeX && coordinateType == CoordinateType.ABSOLUTE, "RelativeVec `x` cannot have relativity while coordinateType is absolute.");
        Check.argCondition(relativeY && coordinateType == CoordinateType.ABSOLUTE, "RelativeVec `y` cannot have relativity while coordinateType is absolute.");
        Check.argCondition(relativeZ && coordinateType == CoordinateType.ABSOLUTE, "RelativeVec `z` cannot have relativity while coordinateType is absolute.");

        // Only XZ for Vec2 types, so we need to check y as well.
        Check.argCondition(coordinateType == CoordinateType.LOCAL && !(relativeX && (relativeY || vec.y() == 0) && relativeZ), "RelativeVec is always relative while coordinateType is local.");
    }

    /**
     * Gets the location based on the relative fields and {@link #vec()}.
     *
     * @param origin the origin position, null if none
     * @return the location
     */
    public Vec from(@Nullable Pos origin) {
        origin = Objects.requireNonNullElse(origin, Pos.ZERO);
        return switch (coordinateType) {
            case RELATIVE -> {
                final double x = vec.x() + (relativeX ? origin.x() : 0);
                final double y = vec.y() + (relativeY ? origin.y() : 0);
                final double z = vec.z() + (relativeZ ? origin.z() : 0);
                yield new Vec(x, y, z);
            }
            case LOCAL -> {
                final Vec vec1 = new Vec(Math.cos(Math.toRadians(origin.yaw() + 90.0f)), 0, Math.sin(Math.toRadians(origin.yaw() + 90.0f)));
                final Vec a = vec1.mul(Math.cos(Math.toRadians(-origin.pitch()))).withY(Math.sin(Math.toRadians(-origin.pitch())));
                final Vec b = vec1.mul(Math.cos(Math.toRadians(-origin.pitch() + 90.0f))).withY(Math.sin(Math.toRadians(-origin.pitch() + 90.0f)));
                final Vec c = a.cross(b).neg();
                final Vec relativePos = a.mul(vec.z()).add(b.mul(vec.y())).add(c.mul(vec.x()));
                yield origin.add(relativePos).asVec();
            }
            case ABSOLUTE -> vec;
        };
    }

    /**
     * Gets the location based on the relative fields.
     *
     * @param entity the entity to get the relative position from
     * @return the location
     */
    public Vec from(@Nullable Entity entity) {
        return from(entity != null ? entity.getPosition() : Pos.ZERO);
    }

    /**
     * Shorthand for {@link #from(Pos)}
     * If player uses their position otherwise, {@link Pos#ZERO}
     *
     * @param sender entity
     * @return the position with any relativity
     */
    public Vec fromSender(@Nullable CommandSender sender) {
        final var entityPosition = sender instanceof Player player ? player.getPosition() : Pos.ZERO;
        return from(entityPosition);
    }

    /**
     * Computes a rotation from this vector, read as (yaw, unused, pitch), relative to the yaw and the pitch of the
     * given point.
     *
     * @param point The reference position used for computing relative coordinates. If null {@link Pos#ZERO}
     * @return (yaw, 0, pitch): the x of this vector plus the yaw of the point if x is relative, and its z plus the
     * pitch of the point if z is relative; this vector itself, y included, when no component is relative
     */
    public Vec fromView(@Nullable Pos point) {
        if (!relativeX && !relativeY && !relativeZ) {
            return vec;
        }
        final var absolute = Objects.requireNonNullElse(point, Pos.ZERO);
        final double x = vec.x() + (relativeX ? absolute.yaw() : 0);
        final double z = vec.z() + (relativeZ ? absolute.pitch() : 0);
        return new Vec(x, 0, z);
    }

    /**
     * Shorthand for {@link #fromView(Pos)}
     * @param entity to get the position from, otherwise {@link Pos#ZERO}
     * @return the view.
     */
    public Vec fromView(@Nullable Entity entity) {
        final var entityPosition = entity != null ? entity.getPosition() : Pos.ZERO;
        return fromView(entityPosition);
    }

    /**
     * Gets if the 'x' field is relative.
     *
     * @return true if the 'x' field is relative
     */
    public boolean isRelativeX() {
        return relativeX;
    }

    /**
     * Gets if the 'y' field is relative.
     *
     * @return true if the 'y' field is relative
     */
    public boolean isRelativeY() {
        return relativeY;
    }

    /**
     * Gets if the 'z' field is relative.
     *
     * @return true if the 'z' field is relative
     */
    public boolean isRelativeZ() {
        return relativeZ;
    }

    public enum CoordinateType {
        /**
         * Relative when any XYZ have the relative flag; unless local.
         */
        RELATIVE,
        /**
         * Local type used in direction, requires full relatively on XZ/XYZ
         */
        LOCAL,
        /**
         * Absolute just returns the original vector.
         */
        ABSOLUTE
    }
}
