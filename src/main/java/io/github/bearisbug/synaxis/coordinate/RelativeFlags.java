package io.github.bearisbug.synaxis.coordinate;

/**
 * The bits of the {@code relative} mask of a teleport, the one {@code PlayerPositionAndLookPacket} and
 * {@code EntityTeleportPacket} carry: which parts of the new position and velocity are relative to the current
 * ones rather than absolute.
 */
public final class RelativeFlags {
    /** Everything absolute. */
    public static final int NONE = 0x00;

    /** The x of the new position is the current x plus the given one, otherwise the given one. */
    public static final int X = 0x01;
    /** The y of the new position is the current y plus the given one, otherwise the given one. */
    public static final int Y = 0x02;
    /** The z of the new position is the current z plus the given one, otherwise the given one. */
    public static final int Z = 0x04;

    /** The new yaw is the current yaw plus the given one, otherwise the given one. */
    public static final int YAW = 0x08;
    /** The new pitch is the current pitch plus the given one, otherwise the given one. */
    public static final int PITCH = 0x10;

    /** The x of the new velocity is the current one plus the given one, otherwise the given one. */
    public static final int DELTA_X = 0x20;
    /** The y of the new velocity is the current one plus the given one, otherwise the given one. */
    public static final int DELTA_Y = 0x40;
    /** The z of the new velocity is the current one plus the given one, otherwise the given one. */
    public static final int DELTA_Z = 0x80;
    /**
     * The client rotates the velocity by the change of the view. Only the client does: the server passes the bit on
     * and leaves its own velocity of the entity unrotated, see {@link PositionUtils#getVelocityWithRelativeFlags}.
     */
    public static final int ROTATE_DELTA = 0x100;

    /** {@link #X}, {@link #Y} and {@link #Z}. */
    public static final int COORD = X | Y | Z;
    /** {@link #YAW} and {@link #PITCH}. */
    public static final int VIEW = YAW | PITCH;
    /** {@link #DELTA_X}, {@link #DELTA_Y} and {@link #DELTA_Z}: the velocity is kept. */
    public static final int DELTA_COORD = DELTA_X | DELTA_Y | DELTA_Z;
    /** {@link #DELTA_COORD} and {@link #ROTATE_DELTA}. */
    public static final int DELTA = DELTA_COORD | ROTATE_DELTA;
    /** {@link #COORD}, {@link #VIEW} and {@link #DELTA}: everything relative. */
    public static final int ALL = COORD | VIEW | DELTA;
}