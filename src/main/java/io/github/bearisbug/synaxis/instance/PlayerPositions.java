package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.Arrays;
import java.util.Collection;

/**
 * Where the players of an instance stood when a tick started, for the dormancy policies of its entities
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-019}). Immutable: taken on the tick scheduler thread before
 * the entities tick, then read by every tick thread.
 */
@ApiStatus.Internal
public final class PlayerPositions {
    // x, y and z of each player, one after the other
    private final double[] coordinates;

    private PlayerPositions(double[] coordinates) {
        this.coordinates = coordinates;
    }

    static PlayerPositions of(Collection<Player> players) {
        double[] coordinates = new double[players.size() * 3];
        int length = 0;
        for (Player player : players) {
            // The set is concurrent and may have grown since its size was read
            if (length == coordinates.length) coordinates = Arrays.copyOf(coordinates, length + 3 * 8);
            final Point position = player.getPosition();
            coordinates[length++] = position.x();
            coordinates[length++] = position.y();
            coordinates[length++] = position.z();
        }
        return new PlayerPositions(length == coordinates.length ? coordinates : Arrays.copyOf(coordinates, length));
    }

    /**
     * The distance from a point to the nearest of the players.
     *
     * @param point the point
     * @return the distance in blocks, {@link Double#POSITIVE_INFINITY} without players
     */
    public double nearestDistance(Point point) {
        final double x = point.x(), y = point.y(), z = point.z();
        double nearest = Double.POSITIVE_INFINITY;
        final double[] coordinates = this.coordinates;
        for (int i = 0; i < coordinates.length; i += 3) {
            final double dx = coordinates[i] - x, dy = coordinates[i + 1] - y, dz = coordinates[i + 2] - z;
            nearest = Math.min(nearest, dx * dx + dy * dy + dz * dz);
        }
        return Math.sqrt(nearest);
    }
}
