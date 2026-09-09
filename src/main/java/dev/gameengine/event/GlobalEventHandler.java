package dev.gameengine.event;

import dev.gameengine.exception.ExceptionManager;

/**
 * Object containing all the global event listeners.
 */
public final class GlobalEventHandler extends EventNodeImpl<Event> {
    private final ExceptionManager exception;

    public GlobalEventHandler(ExceptionManager exception) {
        super("global", EventFilter.ALL, null);
        this.exception = exception;
    }

    @Override
    void handleException(Throwable throwable) {
        exception.handleException(throwable);
    }
}
