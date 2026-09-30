package io.github.bearisbug.synaxis.thread;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

    private final AtomicReference<CountDownLatch> latchRef = new AtomicReference<>();
    private volatile long tickTimeNanos;
    private volatile @Nullable TickObserver tickObserver;

    private long tickNum = 0;
    // In the order they were handed over, which is the tick order; a hash set so that unloading a chunk
    // drops its partition without a scan (docs/DESIGN-M3.md API-M3-006)
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
            final CountDownLatch latch = this.latchRef.get();
            if (latch == null) {
                // Should not happen, but just in case
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
                // #acquire() callbacks
                // The latch is counted down whatever happened: a tick thread dying here would leave
                // every ThreadDispatcher#updateAndAwait blocked on it forever.
                this.latchRef.set(null);
                latch.countDown();
            }
            if (observer != null) observe(observer, System.nanoTime() - start, acquireWaitNanos() - waitedBefore);
            LockSupport.park(this);
        }
    }

    protected void tick() {
        final ReentrantLock lock = this.lock;
        final long tickTime = TimeUnit.NANOSECONDS.toMillis(this.tickTimeNanos);
        for (ThreadDispatcherImpl.Partition entry : entries) {
            assert entry.thread() == this;
            final List<Tickable> elements = entry.elements();
            if (elements.isEmpty()) continue;
            for (Tickable element : elements) {
                if (lock.hasQueuedThreads()) {
                    lock.unlock();
                    // #acquire() callbacks
                    if (!lock.tryLock()) waited(AcquirableImpl.lockTimed(lock));
                }
                try {
                    assert assertElement(element);
                    element.tick(tickTime);
                } catch (Throwable e) {
                    handleException(e);
                }
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

    void startTick(CountDownLatch latch, long tickTimeNanos) {
        CountDownLatch update = latchRef
                .updateAndGet(prevLatch -> prevLatch == null || prevLatch.getCount() == 0 ? latch : prevLatch);
        if (update != latch) {
            // Tick already in progress, wait for it to complete then start our own tick
            try {
                update.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            startTick(latch, tickTimeNanos);
            return;
        }
        if (stop || entries.isEmpty()) {
            // Nothing to tick
            latch.countDown();
            return;
        }
        this.tickTimeNanos = tickTimeNanos;
        this.tickNum++;
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

    public long getTick() {
        return tickNum;
    }

    void shutdown() {
        this.stop = true;
        LockSupport.unpark(this);
    }
}
