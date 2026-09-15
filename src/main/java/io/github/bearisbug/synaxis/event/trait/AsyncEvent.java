package io.github.bearisbug.synaxis.event.trait;

import io.github.bearisbug.synaxis.event.Event;

/**
 * Represents an {@link Event} that is guaranteed to be called within a Virtual Thread.
 */
public interface AsyncEvent extends Event {
}
