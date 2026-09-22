package io.github.bearisbug.synaxis.game.fixture;

import io.github.bearisbug.synaxis.TickSchedulerThread;

/**
 * Stands in for a game class that names an engine type carrying {@code @ApiStatus.Internal} in
 * its signature: {@link TickSchedulerThread} is the engine's own tick loop, marked internal at the
 * class level. Only ever imported as a synthetic {@code JavaClasses}; the main scan of the game
 * package tree leaves it out through {@code DoNotIncludeTests}.
 */
public final class GameClassDeclaringAnEngineInternal {

    private GameClassDeclaringAnEngineInternal() {
    }

    @SuppressWarnings("unused")
    static void hold(TickSchedulerThread thread) {
    }
}
