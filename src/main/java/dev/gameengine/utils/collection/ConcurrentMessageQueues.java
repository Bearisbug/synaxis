package dev.gameengine.utils.collection;

import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpmcUnboundedXaddArrayQueue;
import org.jctools.queues.MpscArrayQueue;
import org.jctools.queues.MpscUnboundedXaddArrayQueue;
import org.jctools.queues.varhandle.MpmcVarHandleArrayQueue;
import org.jctools.queues.varhandle.MpscUnboundedVarHandleArrayQueue;
import org.jctools.queues.varhandle.MpscVarHandleArrayQueue;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ConcurrentMessageQueues {
    /**
     * Picks the {@code sun.misc.Unsafe} backed queues over the VarHandle ones. JVM-wide because these
     * queues are built by objects that have no server process to ask, and because the two
     * implementations only differ in how they access memory.
     */
    private static final boolean UNSAFE_COLLECTIONS = Boolean.getBoolean("gameengine.unsafe-collections");


    public static <T> MessagePassingQueue<T> mpscArrayQueue(int capacity) {
        return UNSAFE_COLLECTIONS ? new MpscArrayQueue<>(capacity) : new MpscVarHandleArrayQueue<>(capacity);
    }

    public static <T> MessagePassingQueue<T> mpscUnboundedArrayQueue(int chunkSize) {
        return UNSAFE_COLLECTIONS ? new MpscUnboundedXaddArrayQueue<>(chunkSize) : new MpscUnboundedVarHandleArrayQueue<>(chunkSize);
    }

    /**
     * There is no unbounded MPMC variant without Unsafe, so {@code capacity} is a chunk size on the
     * unsafe path and a hard capacity on the safe one, where offers are rejected once it is full.
     */
    public static <T> MessagePassingQueue<T> mpmcArrayQueue(int capacity) {
        return UNSAFE_COLLECTIONS ? new MpmcUnboundedXaddArrayQueue<>(capacity) : new MpmcVarHandleArrayQueue<>(capacity);
    }
}