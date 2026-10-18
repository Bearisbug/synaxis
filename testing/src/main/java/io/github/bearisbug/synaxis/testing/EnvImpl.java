package io.github.bearisbug.synaxis.testing;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.EventListener;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.network.player.GameProfile;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

final class EnvImpl implements Env {
    private final ServerProcess process;
    private final List<FlexibleListenerImpl<?>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> caught = new AtomicReference<>();

    public EnvImpl(ServerProcess process) {
        this.process = process;
        // If exceptions reach the exception handler, by default fail the test.
        process().exception().setExceptionHandler(this::handleException);

        // Start the dispatcher threads if not already started.
        process().dispatcher().start();

        // Use player provider to disable queued chunk sending.
        // Set here to allow an individual test to override if they want.
        process.connection().setPlayerProvider(TestConnectionImpl.TestPlayerImpl::new);
    }

    /**
     * The engine calls its exception handler from catch blocks it never expects to throw: a tick
     * thread that gets a throwable back dies before counting its tick latch down, and every later
     * tick blocks forever. So the failure is only recorded here, and reported on the test thread:
     * the first one, with every later one suppressed by it.
     */
    void handleException(Throwable exception) {
        this.caught.updateAndGet(first -> {
            if (first == null) return exception;
            if (first != exception) first.addSuppressed(exception);
            return first;
        });
    }

    private void reportCaughtException() {
        final Throwable exception = this.caught.getAndSet(null);
        if (exception != null) fail("Server threw exception", exception);
    }

    @Override
    public ServerProcess process() {
        return process;
    }

    @Override
    public void tick() {
        Env.super.tick();
        reportCaughtException();
    }

    @Override
    public TestConnection createConnection(GameProfile gameProfile) {
        return new TestConnectionImpl(this, gameProfile);
    }

    @Override
    public <E extends Event, H> Collector<E> trackEvent(Class<E> eventType, EventFilter<? super E, H> filter, H actor) {
        // The node of an entity or an instance is the one it listens on itself: only the tracker's listener
        // comes off it again
        final EventNode<? super E> node = this.process.eventHandler().map(actor, filter);
        final List<E> events = new CopyOnWriteArrayList<>();
        final EventListener<E> listener = EventListener.of(eventType, events::add);
        node.addListener(listener);
        return new EventCollector<>(node, listener, events);
    }

    @Override
    public <E extends Event> FlexibleListener<E> listen(Class<E> eventType) {
        var handler = process.eventHandler();
        var flexible = new FlexibleListenerImpl<>(eventType);
        var listener = EventListener.of(eventType, e -> flexible.handler.accept(e));
        handler.addListener(listener);
        this.listeners.add(flexible);
        return flexible;
    }

    @Override
    public void close() {
        try {
            reportCaughtException();
            this.listeners.forEach(FlexibleListenerImpl::check);
        } finally {
            this.process.stop();
        }
        // What the stop itself threw; outside the finally, so that it never hides a failure of the try
        reportCaughtException();
    }

    private record EventCollector<E extends Event>(EventNode<? super E> node, EventListener<E> listener,
                                                   List<E> events) implements Collector<E> {
        @Override
        public List<E> collect() {
            node.removeListener(listener);
            return List.copyOf(events);
        }
    }

    static final class FlexibleListenerImpl<E extends Event> implements FlexibleListener<E> {
        private final Class<E> eventType;
        private Consumer<E> handler = _ -> {
        };
        private boolean initialized;
        private boolean called;

        FlexibleListenerImpl(Class<E> eventType) {
            this.eventType = eventType;
        }

        @Override
        public void followup(Consumer<E> handler) {
            updateHandler(handler);
        }

        @Override
        public void failFollowup() {
            updateHandler(e -> fail("Event " + e.getClass().getSimpleName() + " was not expected"));
        }

        void updateHandler(Consumer<E> handler) {
            check();
            this.initialized = true;
            this.called = false;
            this.handler = e -> {
                handler.accept(e);
                this.called = true;
            };
        }

        void check() {
            assertTrue(!initialized || called, "Last listener has not been called: " + eventType.getSimpleName());
        }
    }
}
