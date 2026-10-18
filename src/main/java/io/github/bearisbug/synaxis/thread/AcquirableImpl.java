package io.github.bearisbug.synaxis.thread;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

final class AcquirableImpl<T> implements Acquirable<T> {
    private static final boolean ASSERTIONS_ENABLED = AcquirableImpl.class.desiredAssertionStatus();
    /**
     * Turns the ownership assertions on without {@code -ea}. JVM-wide because an acquirable is created
     * in the field initializer of an entity, before the entity is bound to a server process.
     */
    private static final boolean ACQUIRABLE_STRICT = Boolean.getBoolean("synaxis.acquirable-strict");

    /**
     * Global lock used for synchronization.
     */
    static final ReentrantLock GLOBAL_LOCK = new ReentrantLock();

    private final T value;
    private final Thread initThread = Thread.currentThread();
    private volatile @Nullable TickThread assignedThread;

    public AcquirableImpl(T value) {
        this.value = value;
    }

    @Override
    public Acquired<T> lock() {
        final TickThread assignedThread = this.assignedThread;
        if (assignedThread == null) {
            assertInitThread();
            return new AcquiredImpl<>(unwrap(), null);
        }
        ReentrantLock lock = enterAssigned(assignedThread);
        assert isOwned();
        return new AcquiredImpl<>(unwrap(), lock);
    }

    @Override
    public boolean isLocal() {
        final TickThread assignedThread = this.assignedThread;
        return Thread.currentThread() == Objects.requireNonNullElse(assignedThread, initThread);
    }

    @Override
    public boolean isOwned() {
        final TickThread assignedThread = this.assignedThread;
        if (assignedThread == null) return Thread.currentThread() == initThread;
        return AcquirableImpl.isOwnedImpl(assignedThread);
    }

    @Override
    public void sync(Consumer<T> consumer) {
        final TickThread assignedThread = this.assignedThread;
        if (assignedThread == null) {
            assertInitThread();
            consumer.accept(unwrap());
            return;
        }
        ReentrantLock lock = enterAssigned(assignedThread);
        try {
            assert isOwned();
            consumer.accept(unwrap());
        } finally {
            leave(lock);
        }
    }

    @Override
    public boolean trySync(Consumer<T> consumer) {
        if (isOwned()) {
            consumer.accept(unwrap());
            return true;
        }
        // Taking a foreign tick thread lock is ordered by the global lock, see enter(). Grabbing
        // it directly would invert the order against sync() and deadlock, both locks are given up
        // instead: losing either of them is the contention this method reports.
        if (GLOBAL_LOCK.tryLock()) {
            try {
                // Read under the global lock, which the dispatcher holds to hand an element to another
                // thread: read before it, the thread may no longer be the element's
                final TickThread assignedThread = this.assignedThread;
                if (assignedThread != null) {
                    ReentrantLock lock = assignedThread.lock();
                    if (lock.tryLock()) {
                        try {
                            consumer.accept(unwrap());
                            return true;
                        } finally {
                            lock.unlock();
                        }
                    }
                }
            } finally {
                GLOBAL_LOCK.unlock();
            }
        }
        return false;
    }

    @Override
    public T unwrap() {
        return value;
    }

    @Override
    public @UnknownNullability TickThread assignedThread() {
        return assignedThread;
    }

    void assign(TickThread thread) {
        this.assignedThread = thread;
    }

    @Override
    public void assertOwnership() {
        if (!ASSERTIONS_ENABLED && !ACQUIRABLE_STRICT) return;
        TickThread assignedThread = this.assignedThread;
        if (assignedThread == null) {
            // Until the dispatcher assigns it, an element is already reachable from tick threads
            // (tracker, viewers), so a tick thread other than the creator is still a foreign owner.
            // Any other thread passes: a player is built on its connection thread and spawned from the
            // tick scheduler thread, which is not a TickThread (REQ-PROD-019).
            final Thread current = Thread.currentThread();
            if (current == initThread || !(current instanceof TickThread)) return;
            throw new AcquirableOwnershipException(initThread, null, unwrap().toString());
        }
        if (isOwnedImpl(assignedThread)) return;
        throw new AcquirableOwnershipException(initThread, assignedThread, unwrap().toString());
    }

    void assertInitThread() {
        if (Thread.currentThread() != initThread)
            throw new IllegalStateException("Cannot lock an uninitialized Acquirable from a different thread");
    }

    static boolean isOwnedImpl(TickThread elementThread) {
        if (Thread.currentThread() == elementThread) return true;
        return elementThread.lock().isHeldByCurrentThread();
    }

    /**
     * {@link #enter(TickThread)} for this element, starting from {@code thread}, the thread it was
     * assigned to when last read. The dispatcher hands an element to another thread only while it
     * holds the global lock ({@code ThreadDispatcherImpl#updateAndAwait}), so once both locks are
     * held the assignment no longer changes; read before, it may have changed since. The thread
     * entered is then not the element's, and the element's own is entered instead.
     */
    private @Nullable ReentrantLock enterAssigned(TickThread thread) {
        while (true) {
            final ReentrantLock lock = enter(thread);
            final TickThread assigned = Objects.requireNonNull(this.assignedThread);
            if (lock == null || assigned == thread) return lock;
            leave(lock);
            thread = assigned;
        }
    }

    /**
     * Takes the global lock for the dispatcher handing elements to other threads, blocking until it
     * is free. The time blocked is added to the calling thread's own total when it is an
     * {@link EngineThread} ({@code docs/DESIGN-PROD.md} {@code API-PROD-016}).
     */
    static void lockGlobal() {
        if (GLOBAL_LOCK.tryLock()) return;
        final long waited = lockTimed(GLOBAL_LOCK);
        if (Thread.currentThread() instanceof EngineThread engineThread) engineThread.waited(waited);
    }

    /**
     * Takes the global lock, then the lock of {@code elementThread}. The clock is read only for a
     * lock another thread holds: an acquisition that gets both at the first try waited for nothing
     * and costs no clock read. The time blocked is added to the calling thread's own total when it is
     * an {@link EngineThread} ({@code docs/DESIGN-PROD.md} {@code API-PROD-016}).
     */
    static @Nullable ReentrantLock enter(TickThread elementThread) {
        if (isOwnedImpl(elementThread)) return null; // Nothing to lock, already owned by the current thread.
        final Thread current = Thread.currentThread();
        long waited = 0;
        // Enter the target thread
        if (!GLOBAL_LOCK.tryLock()) {
            final long since = System.nanoTime();
            if (current instanceof TickThread tickThread && tickThread.lock().isHeldByCurrentThread()) {
                do {
                    tickThread.lock().unlock();
                    Thread.onSpinWait();
                    tickThread.lock().lock();
                } while (!GLOBAL_LOCK.tryLock());
            } else {
                GLOBAL_LOCK.lock();
            }
            waited = System.nanoTime() - since;
        }
        final ReentrantLock targetLock = elementThread.lock();
        if (!targetLock.tryLock()) waited += lockTimed(targetLock);
        if (waited != 0 && current instanceof EngineThread engineThread) engineThread.waited(waited);
        return targetLock;
    }

    /**
     * Blocks on {@code lock} until this thread holds it.
     *
     * @return how long that took, in nanoseconds
     */
    static long lockTimed(ReentrantLock lock) {
        final long since = System.nanoTime();
        lock.lock();
        return System.nanoTime() - since;
    }

    static void leave(@Nullable ReentrantLock lock) {
        if (lock != null) {
            lock.unlock();
            GLOBAL_LOCK.unlock();
        }
    }
}
