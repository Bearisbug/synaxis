package io.github.bearisbug.synaxis.event.trait;

import io.github.bearisbug.synaxis.entity.Player;

/**
 * Represents an {@link PlayerEvent} which happen in {@link Player#getInstance()}.
 * Useful if you need to listen to player events happening in its instance.
 * <p>
 * Be aware that the player's instance must be non-null.
 */
public interface PlayerInstanceEvent extends PlayerEvent, EntityInstanceEvent {
}
