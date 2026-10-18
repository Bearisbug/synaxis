package io.github.bearisbug.synaxis.event;

import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.RecursiveEvent;
import org.jetbrains.annotations.Contract;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Represents an event listener (handler) in an event graph.
 * <p>
 * A listener is responsible for executing some action based on an event triggering.
 *
 * @param <T> The event type being handled.
 */
public interface EventListener<T extends Event> {

    Class<T> eventType();

    Result run(T event);

    @Contract(pure = true)
    static <T extends Event> EventListener.Builder<T> builder(Class<T> eventType) {
        return new EventListener.Builder<>(eventType);
    }

    /**
     * Create an event listener without any special options. The given listener will be executed
     * if the event passes all parent filtering.
     *
     * @param eventType The event type to handle
     * @param listener  The handler function
     * @param <T>       The event type to handle
     * @return An event listener with the given properties
     */
    @Contract(pure = true)
    static <T extends Event> EventListener<T> of(Class<T> eventType, Consumer<T> listener) {
        if (CancellableEvent.class.isAssignableFrom(eventType) || RecursiveEvent.class.isAssignableFrom(eventType)) {
            return new Builder.ListenerImpl<>(eventType, event -> {
                if (event instanceof CancellableEvent cancellableEvent && cancellableEvent.isCancelled()) {
                    return Result.INVALID;
                }
                listener.accept(event);
                return Result.SUCCESS;
            });
        } else {
            return new Builder.ListenerImpl<>(eventType, event -> {
                listener.accept(event);
                return Result.SUCCESS;
            });
        }
    }

    class Builder<T extends Event> {
        private record ListenerImpl<T extends Event>(
                Class<T> eventType,
                Function<T, EventListener.Result> function
        ) implements EventListener<T> {
            @Override
            public Result run(T t) {
                return function.apply(t);
            }
        }

        private final Class<T> eventType;
        private final List<Predicate<T>> filters = new ArrayList<>();
        private boolean ignoreCancelled = true;
        private int expireCount;
        private Predicate<T> expireWhen;
        private Consumer<T> handler;

        protected Builder(Class<T> eventType) {
            this.eventType = eventType;
        }

        /**
         * Adds a filter to the executor of this listener. The executor will only
         * be called if this condition passes on the given event.
         */
        @Contract(value = "_ -> this")
        public EventListener.Builder<T> filter(Predicate<T> filter) {
            this.filters.add(filter);
            return this;
        }

        /**
         * Specifies if the handler should still be called if {@link CancellableEvent#isCancelled()} returns {@code true}.
         * <p>
         * Default is set to {@code true}.
         *
         * @param ignoreCancelled True to stop processing the event when cancelled
         */
        @Contract(value = "_ -> this")
        public EventListener.Builder<T> ignoreCancelled(boolean ignoreCancelled) {
            this.ignoreCancelled = ignoreCancelled;
            return this;
        }

        /**
         * Removes this listener after it has been executed the given number of times.
         * <p>
         * The handler runs at most that many times, concurrent dispatches included: a run takes its
         * slot before the handler is called, so a handler that throws has used it up too. Events
         * cancelled or refused by a filter take no slot.
         *
         * @param expireCount The number of times to execute
         */
        @Contract(value = "_ -> this")
        public EventListener.Builder<T> expireCount(int expireCount) {
            this.expireCount = expireCount;
            return this;
        }

        /**
         * Expires this listener when it passes the given condition. The expiration will
         * happen before the event is executed.
         *
         * @param expireWhen The condition to test
         */
        @Contract(value = "_ -> this")
        public EventListener.Builder<T> expireWhen(Predicate<T> expireWhen) {
            this.expireWhen = expireWhen;
            return this;
        }

        /**
         * Sets the handler for this event listener. This will be executed if the listener passes
         * all conditions.
         */
        @Contract(value = "_ -> this")
        public EventListener.Builder<T> handler(Consumer<T> handler) {
            this.handler = handler;
            return this;
        }

        @Contract(value = "-> new", pure = true)
        public EventListener<T> build() {
            final boolean ignoreCancelled = this.ignoreCancelled;
            AtomicInteger expirationCount = new AtomicInteger(this.expireCount);
            final boolean hasExpirationCount = expirationCount.get() > 0;

            final Predicate<T> expireWhen = this.expireWhen;

            final var filters = new ArrayList<>(this.filters);
            final var handler = this.handler;
            return new ListenerImpl<>(eventType, event -> {
                // Event cancellation
                if (ignoreCancelled && event instanceof CancellableEvent cancellableEvent &&
                        cancellableEvent.isCancelled()) {
                    return Result.INVALID;
                }
                // Expiration predicate
                if (expireWhen != null && expireWhen.test(event)) {
                    return Result.EXPIRED;
                }
                // Filtering
                if (!filters.isEmpty()) {
                    for (var filter : filters) {
                        if (!filter.test(event)) {
                            // Cancelled
                            return Result.INVALID;
                        }
                    }
                }
                // Expiration count, a slot is taken before the handler so that concurrent
                // dispatches never run it more often than the count
                if (hasExpirationCount) {
                    final int left = expirationCount.decrementAndGet();
                    if (left < 0) return Result.EXPIRED;
                    if (handler != null) handler.accept(event);
                    return left == 0 ? Result.EXPIRED : Result.SUCCESS;
                }
                // Handler
                if (handler != null) {
                    handler.accept(event);
                }
                return Result.SUCCESS;
            });
        }
    }

    enum Result {
        SUCCESS,
        INVALID,
        EXPIRED,
        EXCEPTION
    }
}
