package io.github.bearisbug.synaxis.entity;

/**
 * How far away a player sees an entity ({@code docs/DESIGN-PROD.md} {@code API-PROD-019}): within
 * {@code horizontal} blocks on the horizontal plane and {@code vertical} blocks up or down.
 * <p>
 * The range narrows the square of {@link io.github.bearisbug.synaxis.ServerConfig#entityViewDistance()}
 * chunks around the player and never widens it: an entity outside that square is not seen, whatever
 * its range.
 *
 * @param horizontal the horizontal distance in blocks, positive
 * @param vertical   the vertical distance in blocks, positive
 */
public record TrackingRange(double horizontal, double vertical) {
    public TrackingRange {
        if (!(horizontal > 0) || !(vertical > 0))
            throw new IllegalArgumentException("a tracking range must be positive but was " + horizontal + " by " + vertical);
    }

    /**
     * Whether an entity this far from the player is within the range.
     *
     * @param dx the x distance in blocks
     * @param dy the y distance in blocks
     * @param dz the z distance in blocks
     * @return true if the entity is within the range
     */
    public boolean contains(double dx, double dy, double dz) {
        return dx * dx + dz * dz <= horizontal * horizontal && Math.abs(dy) <= vertical;
    }
}
