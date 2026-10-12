package io.github.bearisbug.synaxis.plugin.play;

import io.github.bearisbug.synaxis.entity.Player;

/**
 * What a plugin does with one action of a player ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-005}).
 */
@FunctionalInterface
public interface ActionHandler {

    /**
     * Handles one action. Called on the tick thread that owns the sender, after the host checked
     * that the action belongs to the current scene, is within the rate limit and is not cooling down.
     *
     * @param player  the sender
     * @param request the action
     * @return the result the host answers with; {@link ActionResult.Ok} starts the slot's cooldown
     */
    ActionResult handle(Player player, ActionRequest request);
}
