package io.github.bearisbug.synaxis.event;

import java.lang.ref.WeakReference;

/**
 * The node {@link EventNode#map} hands out. It joins the holder's dispatch once it gets a listener,
 * a child or a binding, and registers itself in the holder under the same hold of the global lock
 * as that addition: an addition made before the registration is visible would miss the events
 * dispatched on the holder right after it returns.
 */
final class EventNodeLazyImpl<E extends Event> extends EventNodeImpl<E> {
    private final EventNodeImpl<? super E> holder;
    private final WeakReference<Object> owner;
    private boolean mapped; // guarded by GLOBAL_CHILD_LOCK

    EventNodeLazyImpl(EventNodeImpl<? super E> holder,
                      Object owner, EventFilter<E, ?> filter) {
        super(owner.toString(), filter, null);
        this.holder = holder;
        this.owner = new WeakReference<>(owner);
    }

    @Override
    public EventNode<E> addChild(EventNode<? extends E> child) {
        synchronized (GLOBAL_CHILD_LOCK) {
            ensureMap();
            return super.addChild(child);
        }
    }

    @Override
    public EventNode<E> addListener(EventListener<? extends E> listener) {
        // Refused before the node registers itself in the holder
        checkListenable(listener.eventType());
        synchronized (GLOBAL_CHILD_LOCK) {
            ensureMap();
            return super.addListener(listener);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E1 extends E, H> EventNode<E1> map(H value, EventFilter<E1, H> filter) {
        final Object owner = retrieveOwner();
        if (owner != value) {
            throw new IllegalArgumentException("Cannot map an object to an already mapped node.");
        }
        return (EventNode<E1>) this;
    }

    @Override
    public void register(EventBinding<? extends E> binding) {
        for (var eventType : binding.eventTypes()) checkListenable(eventType);
        synchronized (GLOBAL_CHILD_LOCK) {
            ensureMap();
            super.register(binding);
        }
    }

    @SuppressWarnings("unchecked") // the holder's map is typed by the holder's event type
    private void ensureMap() {
        assert Thread.holdsLock(GLOBAL_CHILD_LOCK);
        if (mapped) return;
        // While unmapped the holder has no live entry for this owner: map() hands out one node per
        // owner, and unmap() removes the entry along with resetting the flag
        ((EventNodeImpl<E>) holder).registeredMappedNode.put(retrieveOwner(), new WeakReference<>(this));
        invalidateEventsFor(holder);
        mapped = true;
    }

    /**
     * Called when the node is unmapped, so that a listener added later maps it again.
     */
    void resetMapped() {
        assert Thread.holdsLock(GLOBAL_CHILD_LOCK);
        mapped = false;
    }

    private Object retrieveOwner() {
        final Object owner = this.owner.get();
        if (owner == null) {
            throw new IllegalStateException("Node handle is null. Be sure to never cache a local node.");
        }
        return owner;
    }
}
