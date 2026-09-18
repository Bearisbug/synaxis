package io.github.bearisbug.synaxis.network.packet;

import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPluginMessagePacket;
import io.github.bearisbug.synaxis.utils.collection.ConcurrentMessageQueues;
import org.jctools.queues.MessagePassingQueue;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PacketWriteQueueTest {

    private static MessagePassingQueue<ClientPacket> queueOf(int count, int payloadSize) {
        final var queue = ConcurrentMessageQueues.<ClientPacket>mpscUnboundedArrayQueue(16);
        for (int i = 0; i < count; i++) {
            queue.relaxedOffer(new ClientPluginMessagePacket("synaxis:test", new byte[payloadSize]));
        }
        return queue;
    }

    private static boolean write(NetworkBuffer buffer, ClientPacket packet) {
        PacketWriting.writeFramedPacket(buffer, ConnectionState.CONFIGURATION, packet, 0);
        return true;
    }

    @Test
    public void everyPacketThatFitsIsWrittenAndPolled() {
        final var queue = queueOf(4, 8);
        final NetworkBuffer buffer = NetworkBuffer.staticBuffer(1024);

        PacketWriting.writeQueue(buffer, queue, 1, PacketWriteQueueTest::write);
        assertTrue(queue.isEmpty());
        assertTrue(buffer.writeIndex() > 0);
    }

    @Test
    public void theBufferIsGrownUntilTheMinimumIsWritten() {
        final var queue = queueOf(3, 256);
        // A buffer that does not grow on its own, so the resize has to come from writeQueue
        final NetworkBuffer buffer = NetworkBuffer.staticBuffer(8);

        PacketWriting.writeQueue(buffer, queue, 3, PacketWriteQueueTest::write);
        assertEquals(0, queue.size(), "The minimum has to be reached even if the buffer starts too small");
        assertTrue(buffer.capacity() > 8, "Reaching the minimum required a resize");
    }

    @Test
    public void aPacketLeftInTheQueueDoesNotLeaveHalfAFrameBehind() {
        final var queue = queueOf(2, 64);
        // Room for the first frame only, the buffer cannot grow for the second one
        final NetworkBuffer buffer = NetworkBuffer.staticBuffer(96);

        PacketWriting.writeQueue(buffer, queue, 1, PacketWriteQueueTest::write);
        assertEquals(1, queue.size(), "The packet that did not fit stays in the queue");

        final long written = buffer.writeIndex();
        buffer.readIndex(0);
        final int frameLength = buffer.read(NetworkBuffer.VAR_INT);
        assertEquals(written - 3, frameLength, "Only whole frames may be left in the buffer");
    }

    @Test
    public void theWriterIsCalledAgainForAPacketItRefused() {
        final var queue = queueOf(1, 8);
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(8);
        AtomicInteger attempts = new AtomicInteger();

        PacketWriting.writeQueue(buffer, queue, 1, (b, packet) -> {
            if (attempts.getAndIncrement() == 0) return false;
            return write(b, packet);
        });

        assertEquals(2, attempts.get(), "A refused packet is handed to the writer again");
        assertTrue(queue.isEmpty());
    }
}
