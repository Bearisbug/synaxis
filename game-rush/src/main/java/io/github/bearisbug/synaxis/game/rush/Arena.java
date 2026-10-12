package io.github.bearisbug.synaxis.game.rush;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.generator.Generator;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The ground of the lobby and of a round ({@code docs/DESIGN-PLAY.md} §6.7.3); the skeleton of RUN-077
 * group R, which generates nothing.
 */
final class Arena {

    static final int GROUND = 40;
    static final Pos SPAWN = new Pos(0.5, GROUND, 0.5);
    static final int LOBBY_RADIUS = 1;
    static final int ROUND_RADIUS = 2;

    private Arena() {
    }

    static Generator lobby() {
        return _ -> {
        };
    }

    static Generator round() {
        return _ -> {
        };
    }

    static List<Beacon> beacons() {
        return List.of(new Beacon('A', -12, GROUND, -12), new Beacon('B', 12, GROUND, -12), new Beacon('C', 0, GROUND, 14));
    }

    static Entity label(Instance instance, Beacon beacon) {
        return new Entity(EntityType.TEXT_DISPLAY);
    }

    static void relabel(Entity label, Beacon beacon, @Nullable String owner) {
    }
}
