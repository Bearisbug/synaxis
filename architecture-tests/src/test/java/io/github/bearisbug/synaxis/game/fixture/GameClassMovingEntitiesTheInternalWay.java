package io.github.bearisbug.synaxis.game.fixture;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;

/**
 * Stands in for a game class that advances an entity through the internal
 * {@code Entity#refreshPosition(Pos, boolean, boolean)} instead of the public
 * {@link Entity#moveTo(Pos)}: the violation REQ-M2-025 exists to make impossible. Only ever
 * imported as a synthetic {@code JavaClasses}; the main scan of the game package tree leaves it
 * out through {@code DoNotIncludeTests}.
 * <p>
 * The call goes through a subclass of its own, the way a display entity of the game side would
 * make it: the call site then names the subclass as the owner and only resolving the member finds
 * the engine's annotation, which is what the rule has to do to see the call at all.
 */
public final class GameClassMovingEntitiesTheInternalWay {

    private GameClassMovingEntitiesTheInternalWay() {
    }

    static final class Display extends Entity {
        Display(ServerProcess process) {
            super(process, EntityType.ITEM_DISPLAY);
        }
    }

    @SuppressWarnings("unused")
    static void advance(Display display, Pos target) {
        display.refreshPosition(target, false, true);
    }
}
