package io.github.bearisbug.synaxis.plugin.instance;

import io.github.bearisbug.synaxis.coordinate.Pos;

/**
 * Where a player goes when a managed instance closes ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006}).
 * A player still inside 5 s after the evacuation started is kicked with
 * {@code synaxis:match-unavailable}.
 */
public sealed interface Exit {

    /**
     * Into another managed instance, the way {@link ManagedInstance#move} moves a player.
     *
     * @param target the instance
     * @param at     where the player lands
     */
    record ToInstance(ManagedInstance target, Pos at) implements Exit {
    }

    /**
     * Off the server.
     *
     * @param reasonKey the disconnect reason key
     */
    record Kick(String reasonKey) implements Exit {
    }
}
