package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.JvmFlags;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginPluginRequestPacket;
import io.github.bearisbug.synaxis.utils.collection.ConcurrentMessageQueues;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        // No cap on the payload of a login plugin request, unlike the client-bound plugin message (1 MiB)
        queue.relaxedOffer(new LoginPluginRequestPacket(1, "t", new byte[JvmFlags.MAX_PACKET_SIZE + 1]));

        final NetworkBuffer buffer = NetworkBuffer.staticBuffer(
                env.process().config().pooledBufferSize(), env.process().registries());
        final IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PacketWriting.writeQueue(buffer, queue, 1, (b, packet) -> {
            PacketWriting.writeFramedPacket(b, ConnectionState.LOGIN, packet, 0);
            return true;
        }));
        assertTrue(queue.isEmpty(), "a packet that cannot be written must leave the queue");
        // The class and the size, not the fields of the packet: a list in a record prints all of it
        assertEquals("Packet too large to be written: LoginPluginRequestPacket > " + JvmFlags.MAX_PACKET_SIZE
                + " (synaxis.max-packet-size)", error.getMessage());
    }
}
