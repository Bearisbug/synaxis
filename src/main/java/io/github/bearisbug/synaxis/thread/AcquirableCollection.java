package io.github.bearisbug.synaxis.thread;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Stream;

@ApiStatus.Experimental
public class AcquirableCollection<E> implements Collection<Acquirable<E>> {
    private final Collection<Acquirable<E>> acquirableCollection;

    public AcquirableCollection(Collection<Acquirable<E>> acquirableCollection) {
        this.acquirableCollection = acquirableCollection;
    }

    public void acquireSync(Consumer<E> consumer) {
        // Separate the elements by thread, to acquire each thread once
        final Map<TickThread, List<Acquirable<E>>> threadElementsMap = new HashMap<>();
        for (Acquirable<E> element : acquirableCollection) {
            final TickThread elementThread = element.assignedThread();
            if (elementThread == null || Thread.currentThread() == elementThread) {
                // Never dispatched (sync falls back to its init thread) or managed in the current thread
                element.sync(consumer);
            } else {
                threadElementsMap.computeIfAbsent(elementThread, _ -> new ArrayList<>()).add(element);
            }
        }
        // Acquire all the threads one by one
        for (Map.Entry<TickThread, List<Acquirable<E>>> entry : threadElementsMap.entrySet()) {
            final TickThread tickThread = entry.getKey();
            final List<Acquirable<E>> moved = new ArrayList<>();
            ReentrantLock lock = AcquirableImpl.enter(tickThread);
            try {
                for (Acquirable<E> element : entry.getValue()) {
                    // Handed to another thread since it was read: the dispatcher no longer moves it now
                    // that the global lock is held, it is acquired on its own thread below
                    if (element.assignedThread() == tickThread) consumer.accept(element.unwrap());
                    else moved.add(element);
                }
            } finally {
                AcquirableImpl.leave(lock);
            }
            for (Acquirable<E> element : moved) element.sync(consumer);
        }
    }

    public Stream<E> unwrap() {
        return acquirableCollection.stream().map(Acquirable::unwrap);
    }

    @Override
    public int size() {
        return acquirableCollection.size();
    }

    @Override
    public boolean isEmpty() {
        return acquirableCollection.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return acquirableCollection.contains(o);
    }

    @Override
    public Iterator<Acquirable<E>> iterator() {
        return acquirableCollection.iterator();
    }

    @Override
    public Object[] toArray() {
        return acquirableCollection.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return acquirableCollection.toArray(a);
    }

    @Override
    public boolean add(Acquirable<E> eAcquirable) {
        return acquirableCollection.add(eAcquirable);
    }

    @Override
    public boolean remove(Object o) {
        return acquirableCollection.remove(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return acquirableCollection.containsAll(c);
    }

    @Override
    public boolean addAll(Collection<? extends Acquirable<E>> c) {
        return acquirableCollection.addAll(c);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return acquirableCollection.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return acquirableCollection.retainAll(c);
    }

    @Override
    public void clear() {
        this.acquirableCollection.clear();
    }

    /**
     * Separates a collection of acquirable elements into a map of thread to elements,
     * consuming immediately the elements already owned by the current thread.
     *
     * @param collection the acquirable collection
     * @param consumer   the consumer to execute when an element is already in the current thread
     * @return a new Thread to acquirable elements map
     */
    protected static <T> Map<TickThread, List<T>> retrieveOptionalThreadMap(Collection<Acquirable<T>> collection,
                                                                            Consumer<T> consumer) {
        // Separate a collection of acquirable elements into a map of thread->elements
        // Useful to reduce the number of acquisition
        Map<TickThread, List<T>> threadCacheMap = new HashMap<>();
        for (var element : collection) {
            final T value = element.unwrap();
            final TickThread elementThread = element.assignedThread();
            if (elementThread == null) {
                // The element has never been dispatched, Acquirable#sync falls back to its init thread
                element.sync(consumer);
            } else if (Thread.currentThread() == elementThread) {
                // The element is managed in the current thread, consumer can be immediately called
                consumer.accept(value);
            } else {
                // The element is manager in a different thread, cache it
                List<T> threadCacheList = threadCacheMap.computeIfAbsent(elementThread, _ -> new ArrayList<>());
                threadCacheList.add(value);
            }
        }
        return threadCacheMap;
    }
}
