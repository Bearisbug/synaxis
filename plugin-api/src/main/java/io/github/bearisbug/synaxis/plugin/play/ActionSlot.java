package io.github.bearisbug.synaxis.plugin.play;

import net.kyori.adventure.text.Component;

import java.time.Duration;

/**
 * One action key of a scene ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-005}).
 *
 * @param slot     the action key, 0 to 5
 * @param actionId the action it sends, registered with {@link PluginPlay#onAction}, with or without
 *                 this plugin's prefix
 * @param label    what the key is labelled with
 * @param cooldown how long the key is refused after an accepted action, 0 to 600 s; the client
 *                 starts the same cooldown locally when the key is pressed
 */
public record ActionSlot(int slot, String actionId, Component label, Duration cooldown) {
}
