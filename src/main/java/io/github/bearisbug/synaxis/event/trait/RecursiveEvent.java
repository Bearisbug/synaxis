package io.github.bearisbug.synaxis.event.trait;

import io.github.bearisbug.synaxis.event.Event;
import org.jetbrains.annotations.ApiStatus;

/**
 * An event whose listeners include those of its superclasses. A listener is otherwise keyed on the
 * exact runtime class of the event: dispatching a {@code RecursiveEvent} also runs the listeners
 * registered for each of its superclasses that is a {@code RecursiveEvent} too, up to the first one
 * that is not. Interfaces are never walked, so a listener type has to be a class
 * ({@link io.github.bearisbug.synaxis.event.EventNode#addListener(io.github.bearisbug.synaxis.event.EventListener)}).
 */
@ApiStatus.Experimental
public interface RecursiveEvent extends Event {
}
