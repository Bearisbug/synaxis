package io.github.bearisbug.synaxis.plugin.play;

/**
 * The cursor mode of a scene ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-005}). Version 1 of
 * {@code synaxis:play} has only the locked cursor; a free cursor arrives with version 3.
 */
public enum CursorMode {

    /**
     * The vanilla cursor, captured by the game while no screen is open; wire code 0.
     */
    LOCKED
}
