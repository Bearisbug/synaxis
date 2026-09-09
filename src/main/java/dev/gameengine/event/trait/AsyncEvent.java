package dev.gameengine.event.trait;

import dev.gameengine.event.Event;

/**
 * Represents an {@link Event} that is guaranteed to be called within a Virtual Thread.
 */
public interface AsyncEvent extends Event {
}
