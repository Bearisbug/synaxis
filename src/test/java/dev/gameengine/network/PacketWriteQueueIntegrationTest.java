package dev.gameengine.network;

import dev.gameengine.MinecraftServer;
import dev.gameengine.ServerFlag;
import dev.gameengine.network.packet.PacketWriting;
import dev.gameengine.network.packet.server.ServerPacket;
import dev.gameengine.network.packet.server.common.PluginMessagePacket;
import dev.gameengine.utils.collection.ConcurrentMessageQueues;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
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
