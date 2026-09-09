package net.minestom.server.network;

import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.network.packet.PacketWriting;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.common.PluginMessagePacket;
import net.minestom.server.utils.collection.ConcurrentMessageQueues;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class PacketWriteQueueTest {

    @BeforeAll
    public static void setup(Env env) {
        assertNotNull(env.process().registries());
    }

    @Test
    public void aPacketTooLargeToEverFitIsDroppedNotRetried() {
        final var queue = ConcurrentMessageQueues.<ServerPacket>mpscUnboundedArrayQueue(16);
        queue.relaxedOffer(new PluginMessagePacket("t", new byte[ServerFlag.MAX_PACKET_SIZE + 1]));

        final NetworkBuffer buffer = NetworkBuffer.staticBuffer(
                ServerFlag.POOLED_BUFFER_SIZE, MinecraftServer.getRegistries());
        assertThrows(IllegalStateException.class, () -> PacketWriting.writeQueue(buffer, queue, 1, (b, packet) -> {
            PacketWriting.writeFramedPacket(b, ConnectionState.PLAY, packet, 0);
            return true;
        }));
        assertTrue(queue.isEmpty(), "a packet that cannot be written must leave the queue");
    }
}
