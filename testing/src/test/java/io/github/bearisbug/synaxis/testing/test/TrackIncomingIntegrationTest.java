package io.github.bearisbug.synaxis.testing.test;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.DynamicChunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.BufferedPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@EnvTest
public class TrackIncomingIntegrationTest {

    /**
     * A chunk may hand out its packet already encoded, as a {@code .synx} handle chunk does. A
     * {@link BufferedPacket} holds bytes, not a {@link ServerPacket}, so the trackers pass over it
     * the way the outgoing packet event does, and still collect the chunk packets around it.
     */
    @Test
    void trackersPassOverABufferedChunkPacket(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        var incoming = connection.trackIncoming();

        player.sendChunk(new EncodedChunk(instance, 3, 3));
        player.sendChunk(instance.loadChunk(0, 0).join());

        List<ServerPacket> packets = incoming.collect();
        assertEquals(1, packets.size(), () -> "tracked " + packets);
        var chunk = assertInstanceOf(ChunkDataPacket.class, packets.getFirst());
        assertEquals(0, chunk.chunkX());
        assertEquals(0, chunk.chunkZ());
    }

    private static final class EncodedChunk extends DynamicChunk {
        private static final byte[] BYTES = {1, 0};

        EncodedChunk(Instance instance, int chunkX, int chunkZ) {
            super(instance, chunkX, chunkZ);
        }

        @Override
        public SendablePacket getFullDataPacket() {
            return new BufferedPacket(NetworkBuffer.wrap(BYTES, 0, BYTES.length), 0, BYTES.length, false);
        }
    }
}
