package io.github.bearisbug.synaxis.game.rush;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Pos;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;

/**
 * One beacon of a round ({@code docs/DESIGN-PLAY.md} §6.7.2); the skeleton of RUN-077 group R.
 */
final class Beacon {

    private final char letter;
    private final int x;
    private final int y;
    private final int z;

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

    Pos label() {
        return new Pos(x + 0.5, y + 2.5, z + 0.5);
    }

    boolean contains(Point position) {
        return false;
    }

    @Nullable UUID owner() {
        return null;
    }

    int percent() {
        return 0;
    }

    @Nullable UUID advance(Set<UUID> inside, long nanos) {
        return null;
    }

    void forget(UUID player) {
    }
}
