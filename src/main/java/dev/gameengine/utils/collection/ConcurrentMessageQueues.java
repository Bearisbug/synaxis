package dev.gameengine.utils.collection;

import dev.gameengine.ServerFlag;
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

    public static <T> MessagePassingQueue<T> mpscArrayQueue(int capacity) {
        return ServerFlag.UNSAFE_COLLECTIONS ? new MpscArrayQueue<>(capacity) : new MpscVarHandleArrayQueue<>(capacity);
    }

    public static <T> MessagePassingQueue<T> mpscUnboundedArrayQueue(int chunkSize) {
        return ServerFlag.UNSAFE_COLLECTIONS ? new MpscUnboundedXaddArrayQueue<>(chunkSize) : new MpscUnboundedVarHandleArrayQueue<>(chunkSize);
    }

    /**
     * There is no unbounded MPMC variant without Unsafe, so {@code capacity} is a chunk size on the
     * unsafe path and a hard capacity on the safe one, where offers are rejected once it is full.
     */
    public static <T> MessagePassingQueue<T> mpmcArrayQueue(int capacity) {
        return ServerFlag.UNSAFE_COLLECTIONS ? new MpmcUnboundedXaddArrayQueue<>(capacity) : new MpmcVarHandleArrayQueue<>(capacity);
    }
}