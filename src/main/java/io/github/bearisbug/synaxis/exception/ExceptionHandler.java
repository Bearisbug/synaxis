package io.github.bearisbug.synaxis.exception;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Used when you want to implement your own exception handling, instead of the default log line.
 * <p>
 * Sets with {@link ExceptionManager#setExceptionHandler(ExceptionHandler)}.
 */
@FunctionalInterface
public interface ExceptionHandler {

    /**
     * Called when an exception was caught.
     *
     * @param e the thrown exception
     */
    void handleException(Throwable e);

    /**
     * Called when an exception was caught while handling one connection, so that the report can
     * name it ({@code docs/DESIGN-PROD.md} {@code API-PROD-013}). A handler that has no use for the
     * connection gets the plain {@link #handleException(Throwable)}.
     *
     * @param e            the thrown exception
     * @param connectionId the connection it was thrown for, {@code null} when there is none
     */
    default void handleException(Throwable e, @Nullable UUID connectionId) {
        handleException(e);
    }
}
