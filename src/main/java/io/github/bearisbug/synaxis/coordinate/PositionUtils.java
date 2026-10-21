package io.github.bearisbug.synaxis.coordinate;

import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class PositionUtils {
    public static Pos getPositionWithRelativeFlags(Pos start, Pos modifier, @MagicConstant(flagsFromClass = RelativeFlags.class) int flags) {
        double x = (flags & RelativeFlags.X) == 0 ? modifier.x() : start.x() + modifier.x();
        double y = (flags & RelativeFlags.Y) == 0 ? modifier.y() : start.y() + modifier.y();
        double z = (flags & RelativeFlags.Z) == 0 ? modifier.z() : start.z() + modifier.z();
        float yaw = (flags & RelativeFlags.YAW) == 0 ? modifier.yaw() : start.yaw() + modifier.yaw();
        float pitch = (flags & RelativeFlags.PITCH) == 0 ? modifier.pitch() : start.pitch() + modifier.pitch();
        return new Pos(x, y, z, yaw, pitch);
    }

    /**
     * The velocity after a teleport with {@code flags}: each component relative to {@code start} when its
     * {@link RelativeFlags#DELTA_X DELTA_*} bit is set, {@code modifier} as is otherwise.
     * <p>
     * {@link RelativeFlags#ROTATE_DELTA} is not applied: the server does not rotate the velocity, only the client
     * does, so a teleport with {@link RelativeFlags#DELTA} or {@link RelativeFlags#ALL} that changes the view of an
     * entity moving leaves the two velocities apart.
     */
    public static Vec getVelocityWithRelativeFlags(Vec start, Vec modifier, @MagicConstant(flagsFromClass = RelativeFlags.class) int flags) {
        double x = (flags & RelativeFlags.DELTA_X) == 0 ? modifier.x() : start.x() + modifier.x();
        double y = (flags & RelativeFlags.DELTA_Y) == 0 ? modifier.y() : start.y() + modifier.y();
        double z = (flags & RelativeFlags.DELTA_Z) == 0 ? modifier.z() : start.z() + modifier.z();
        return new Vec(x, y, z);
    }
}
