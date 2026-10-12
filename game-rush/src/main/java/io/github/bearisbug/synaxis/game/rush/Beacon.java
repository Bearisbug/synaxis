package io.github.bearisbug.synaxis.game.rush;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Pos;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * One beacon of a round and who holds it ({@code docs/DESIGN-PLAY.md} §6.7.2): pure rules, the time
 * comes in as the length of each step.
 * <p>
 * A beacon has a holder, the player its progress belongs to, and an owner, the player who filled it
 * up. With exactly one participant in the circle the progress moves toward that one: it rises at 10%
 * a second when the beacon is theirs or nobody's, and drains at 20% a second when it is someone
 * else's, who loses it on reaching zero. With two or more in the circle it stands still, and with
 * nobody it stays as it is. The owner scores until the beacon is drained, not just until someone
 * steps in.
 */
final class Beacon {

    /** The horizontal reach of the circle, in blocks from the centre of the beacon block. */
    static final double RADIUS = 3;

    /** The vertical reach of the circle, in blocks above or below the beacon block. */
    static final double REACH = 2;

    /** The time one player alone takes to fill a beacon nobody holds, 10 s at 10% a second. */
    static final long FULL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final char letter;
    private final int x;
    private final int y;
    private final int z;
    private @Nullable UUID owner;
    private @Nullable UUID holder;
    /** Filled time of the holder, 0 to {@link #FULL_NANOS}; draining takes it off twice as fast. */
    private long progress;

    /**
     * Creates a beacon nobody holds.
     *
     * @param letter the name the players see
     * @param x      the x of the beacon block
     * @param y      the y of the beacon block, which is the height the players stand at
     * @param z      the z of the beacon block
     */
    Beacon(char letter, int x, int y, int z) {
        this.letter = letter;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    char letter() {
        return letter;
    }

    int x() {
        return x;
    }

    int y() {
        return y;
    }

    int z() {
        return z;
    }

    /**
     * Where the label of the beacon floats.
     *
     * @return a point above the beacon block
     */
    Pos label() {
        return new Pos(x + 0.5, y + 2.5, z + 0.5);
    }

    /**
     * Whether a position is in the circle of the beacon.
     *
     * @param position the position of a player
     * @return true within {@link #RADIUS} horizontally and {@link #REACH} vertically
     */
    boolean contains(Point position) {
        final double dx = position.x() - (x + 0.5);
        final double dz = position.z() - (z + 0.5);
        return dx * dx + dz * dz <= RADIUS * RADIUS && Math.abs(position.y() - y) <= REACH;
    }

    /**
     * The player who filled the beacon up and scores for it.
     *
     * @return the owner, null when nobody owns it
     */
    @Nullable UUID owner() {
        return owner;
    }

    /**
     * How full the beacon is for its holder.
     *
     * @return 0 to 100
     */
    int percent() {
        return (int) (progress * 100 / FULL_NANOS);
    }

    /**
     * Moves the progress on by one step.
     *
     * @param inside the participants in the circle
     * @param nanos  the length of the step
     * @return the player who filled the beacon up in this step, null if nobody did
     */
    @Nullable UUID advance(Set<UUID> inside, long nanos) {
        if (inside.size() != 1) return null;
        final UUID player = inside.iterator().next();
        long left = nanos;
        if (holder != null && !holder.equals(player)) {
            final long drain = (progress + 1) / 2;
            if (left < drain) {
                progress -= 2 * left;
                return null;
            }
            left -= drain;
            progress = 0;
            holder = null;
            owner = null;
        }
        holder = player;
        progress = Math.min(FULL_NANOS, progress + left);
        if (progress < FULL_NANOS || player.equals(owner)) return null;
        owner = player;
        return player;
    }

    /**
     * Drops a participant who left the round: a beacon they hold goes back to nobody, empty.
     *
     * @param player the participant
     */
    void forget(UUID player) {
        if (!player.equals(holder)) return;
        holder = null;
        owner = null;
        progress = 0;
    }
}
