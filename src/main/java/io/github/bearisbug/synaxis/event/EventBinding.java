package io.github.bearisbug.synaxis.event;

import org.jetbrains.annotations.ApiStatus;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

@ApiStatus.Experimental
public interface EventBinding<E extends Event> {

    static <E extends Event, T> FilteredBuilder<E, T> filtered(EventFilter<E, T> filter, Predicate<T> predicate) {
        return new FilteredBuilder<>(filter, predicate);
    }

    /**
     * The event types this binding has a consumer for. The set must not change after the binding is
     * registered.
     *
     * @return the bound event types
     */
    Collection<Class<? extends Event>> eventTypes();

    /**
     * The consumer of one of the {@link #eventTypes()}. Must return the same (equal) instance on
     * every call for a given type: {@link EventNode#register} deduplicates and
     * {@link EventNode#unregister} removes by equals, so a fresh lambda per call is registered again
     * on each register and is never removed by unregister.
     *
     * @param eventType one of the bound event types
     * @return the consumer of that type
     */
    Consumer<E> consumer(Class<? extends Event> eventType);

    class FilteredBuilder<E extends Event, T> {
        private final EventFilter<E, T> filter;
        private final Predicate<T> predicate;
        private final Map<Class<? extends Event>, BiConsumer<Object, E>> mapped = new HashMap<>();

        FilteredBuilder(EventFilter<E, T> filter, Predicate<T> predicate) {
            this.filter = filter;
            this.predicate = predicate;
        }

        @SuppressWarnings("unchecked")
        public <M extends E> FilteredBuilder<E, T> map(Class<M> eventType,
                                                       BiConsumer<T, M> consumer) {
            this.mapped.put(eventType, (BiConsumer<Object, E>) consumer);
            return this;
        }

        public EventBinding<E> build() {
            final var copy = Map.copyOf(mapped);
            final var eventTypes = copy.keySet();

            Map<Class<? extends Event>, Consumer<E>> consumers = new HashMap<>(eventTypes.size());
            for (var eventType : eventTypes) {
                final var consumer = copy.get(eventType);
                consumers.put(eventType, event -> {
                    final T handler = filter.getHandler(event);
                    if (!predicate.test(handler)) return;
                    consumer.accept(handler, event);
                });
            }
            return new EventBinding<>() {
                @Override
                public Set<Class<? extends Event>> eventTypes() {
                    return eventTypes;
                }

                @Override
                public Consumer<E> consumer(Class<? extends Event> eventType) {
                    return consumers.get(eventType);
                }
            };
        }
    }
}
