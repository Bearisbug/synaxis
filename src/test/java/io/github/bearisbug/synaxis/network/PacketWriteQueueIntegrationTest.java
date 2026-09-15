package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.JvmFlags;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.utils.collection.ConcurrentMessageQueues;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class PacketWriteQueueIntegrationTest {

    @BeforeAll
    public static void setup(Env env) {
        assertNotNull(env.process().registries());
    }

    @Test
    public void aPacketTooLargeToEverFitIsDroppedNotRetried(Env env) {
        final var queue = ConcurrentMessageQueues.<ServerPacket>mpscUnboundedArrayQueue(16);
        queue.relaxedOffer(new PluginMessagePacket("t", new byte[JvmFlags.MAX_PACKET_SIZE + 1]));

        final NetworkBuffer buffer = NetworkBuffer.staticBuffer(
                env.process().config().pooledBufferSize(), env.process().registries());
        assertThrows(IllegalStateException.class, () -> PacketWriting.writeQueue(buffer, queue, 1, (b, packet) -> {
            PacketWriting.writeFramedPacket(b, ConnectionState.PLAY, packet, 0);
            return true;
        }));
        assertTrue(queue.isEmpty(), "a packet that cannot be written must leave the queue");
    }
}
