package io.github.bearisbug.synaxis.plugin.play;

import io.github.bearisbug.synaxis.protocol.play.PlayProtocol;
import io.github.bearisbug.synaxis.protocol.play.S2CScene;
import net.kyori.adventure.text.Component;

import java.time.Duration;

/**
 * One action key of a scene ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-005}).
 *
 * @param slot     the action key, 0 to one less than
 *                 {@value io.github.bearisbug.synaxis.protocol.play.S2CScene#MAX_ACTIONS}
 * @param actionId the action it sends, registered with {@link PluginPlay#onAction}, with or without
 *                 this plugin's prefix
 * @param label    what the key is labelled with
 * @param cooldown how long the key is refused after an accepted action, 0 to
 *                 {@value io.github.bearisbug.synaxis.protocol.play.PlayProtocol#MAX_COOLDOWN_MS} ms;
 *                 the client starts the same cooldown locally when the key is pressed
 */
public record ActionSlot(int slot, String actionId, Component label, Duration cooldown) {

    /**
     * Checks the ranges above.
     *
     * @throws IllegalArgumentException if the slot or the cooldown is out of range
     */
    public ActionSlot {
        if (slot < 0 || slot >= S2CScene.MAX_ACTIONS)
            throw new IllegalArgumentException("an action key is 0 to " + (S2CScene.MAX_ACTIONS - 1) + ", not " + slot);
        if (cooldown.isNegative() || cooldown.compareTo(Duration.ofMillis(PlayProtocol.MAX_COOLDOWN_MS)) > 0)
            throw new IllegalArgumentException("a cooldown is 0 to " + PlayProtocol.MAX_COOLDOWN_MS / 1000 + " s, not "
                    + cooldown);
    }
}
