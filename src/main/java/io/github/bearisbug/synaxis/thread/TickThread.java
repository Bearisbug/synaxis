package io.github.bearisbug.synaxis.thread;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Thread responsible for ticking the partitions and elements it was handed.
 * <p>
 * Created in {@link ThreadDispatcher}, and awaken every tick with a task to execute.
 */
@ApiStatus.Internal
public class TickThread extends EngineThread {
    public static final String THREAD_NAME_TICK = "Syn-Tick";

    private final Consumer<Throwable> exceptionHandler;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean stop;

    // The latch of the tick this thread has to run, null once it ran it: written before the unpark
    // that wakes this thread, which orders the writes before it ahead of this thread's reads
    private volatile @Nullable CountDownLatch latch;
    private volatile long tickTimeNanos;
    private volatile @Nullable TickObserver tickObserver;

    // In the order they were handed over, which is the tick order; a hash set so that unloading a chunk
    // drops its partition without a scan (docs/DESIGN-M3.md API-M3-006). Only the dispatcher changes
    // it, in ThreadDispatcherImpl#updateAndAwait and #refreshThreads, while this thread is between two
    // ticks: the latch of the tick before is counted down, the next one not yet published.
    final Set<ThreadDispatcherImpl.Partition> entries = new LinkedHashSet<>();

    public TickThread(int number, Consumer<Throwable> exceptionHandler) {
        this(THREAD_NAME_TICK + "-" + number, exceptionHandler);
    }

    public TickThread(String name, Consumer<Throwable> exceptionHandler) {
        super(name);
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void run() {
        LockSupport.park(this); // Wait for first tick
        while (!stop) {
            final CountDownLatch latch = this.latch;
            if (latch == null) {
                // park() returned spuriously or on a permit left over, or this thread was handed no
                // partition: only a published latch means a tick to run
                LockSupport.park(this);
                continue;
            }
            final ReentrantLock lock = this.lock;
            final TickObserver observer = this.tickObserver;
            final long start = observer != null ? System.nanoTime() : 0;
            final long waitedBefore = acquireWaitNanos();
            // An acquirer may hold it from before the tick, blocking on it is a wait of this tick
            // (docs/DESIGN-PROD.md API-PROD-016)
            if (!lock.tryLock()) waited(AcquirableImpl.lockTimed(lock));
            try {
                tick();
            } catch (Throwable e) {
                handleException(e);
            } finally {
                lock.unlock();
                // The latch is counted down whatever happened: a tick thread dying here would leave
                // every ThreadDispatcher#updateAndAwait blocked on it forever. Cleared first, so that
                // the latch of the next tick is never overwritten.
                this.latch = null;
                latch.countDown();
            }
            if (observer != null) observe(observer, System.nanoTime() - start, acquireWaitNanos() - waitedBefore);
            LockSupport.park(this);
        }
    }

    protected void tick() {
        final ReentrantLock lock = this.lock;
        final long tickTime = TimeUnit.NANOSECONDS.toMillis(this.tickTimeNanos);
        // The holds of the global lock the elements ticked so far left behind
        int globalHolds = 0;
        for (ThreadDispatcherImpl.Partition entry : entries) {
            assert entry.thread() == this;
            final List<Tickable> elements = entry.elements();
            if (elements.isEmpty()) continue;
            for (Tickable element : elements) {
                if (lock.hasQueuedThreads()) {
                    lock.unlock();
                    // Hand the lock to the queued acquirers between two elements (docs/DESIGN-PROD.md API-PROD-016 ③)
                    if (!lock.tryLock()) waited(AcquirableImpl.lockTimed(lock));
                }
                try {
                    assert assertElement(element);
                    element.tick(tickTime);
                } catch (Throwable e) {
                    handleException(e);
                }
                // An element that did not unlock what it acquired keeps the global lock, and the lock
                // of the thread that owns what it acquired: the next tick waits on them forever
                final int holds = AcquirableImpl.GLOBAL_LOCK.getHoldCount();
                if (holds > globalHolds) handleException(new IllegalStateException(element + " left an Acquired locked"));
                globalHolds = holds;
            }
        }
    }

    /**
     * Told of every tick of a tick thread, on that thread right after the tick.
     */
    @FunctionalInterface
    public interface TickObserver {
        /**
         * Called once per tick of the thread.
         *
         * @param tickNanos    how long the tick took, in nanoseconds
         * @param acquireNanos how much of it the thread was blocked on a lock of the acquisition
         *                     protocol, in nanoseconds ({@link TickThread#acquireWaitNanos()})
         */
        void ticked(long tickNanos, long acquireNanos);
    }

    /**
     * Tells {@code observer} how long each tick of this thread took and how long the thread waited
     * to acquire during it, on this thread right after the tick ({@code docs/DESIGN-PROD.md}
     * {@code API-PROD-015}). A thread handed no partition does not tick and reports nothing.
     *
     * @param observer the observer, null to stop observing
     */
    public void observeTicks(@Nullable TickObserver observer) {
        this.tickObserver = observer;
    }

    private void observe(TickObserver observer, long nanos, long acquireNanos) {
        try {
            observer.ticked(nanos, acquireNanos);
        } catch (Throwable e) {
            handleException(e);
        }
    }

    /**
     * Reports an exception without ever letting the handler's own failure escape the tick loop.
     */
    private void handleException(Throwable throwable) {
        try {
            exceptionHandler.accept(throwable);
        } catch (Throwable handlerFailure) {
            // Handlers usually rethrow the original as the cause, suppressing it too would print it twice.
            if (handlerFailure != throwable && handlerFailure.getCause() != throwable)
                handlerFailure.addSuppressed(throwable);
            handlerFailure.printStackTrace();
        }
    }

    private boolean assertElement(Tickable element) {
        return !(element instanceof AcquirableSource<?> source)
                || (source.acquirable().assignedThread() == this &&
                source.acquirable().assignedThread().lock().isHeldByCurrentThread());
    }

    /**
     * Hands this thread a tick, or counts {@code latch} down at once when it has nothing to tick. Only
     * called by {@link ThreadDispatcherImpl#updateAndAwait(long)}, which waits for every latch of a tick
     * before it starts the next one.
     */
    void startTick(CountDownLatch latch, long tickTimeNanos) {
        assert this.latch == null : "the previous tick is still running";
        if (stop || entries.isEmpty()) {
            // Nothing to tick
            latch.countDown();
            return;
        }
        this.tickTimeNanos = tickTimeNanos;
        this.latch = latch;
        LockSupport.unpark(this);
    }

    /**
     * Gets the lock used to ensure the safety of entity acquisition.
     *
     * @return the thread lock
     */
    public ReentrantLock lock() {
        return lock;
    }

    void shutdown() {
        this.stop = true;
        LockSupport.unpark(this);
    }
}
