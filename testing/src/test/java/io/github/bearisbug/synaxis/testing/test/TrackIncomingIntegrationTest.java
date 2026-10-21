package io.github.bearisbug.synaxis.testing.test;

import io.github.bearisbug.synaxis.JvmFlags;
import io.github.bearisbug.synaxis.adventure.EngineAdventure;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.DynamicChunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.BufferedPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SystemChatPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    /**
     * A collector tracks until its first {@code collect()}, which every assertion calls: a packet
     * sent after the first assertion is not in what a later one sees.
     */
    @Test
    void aCollectorStopsTrackingAtItsFirstCollect(Env env) {
        var connection = env.createConnection();
        var player = connection.connect(env.createFlatInstance(), new Pos(0, 42, 0));
        var chat = connection.trackIncoming(SystemChatPacket.class);

        player.sendPacket(new SystemChatPacket(Component.text("first"), false));
        chat.assertSingle();
        player.sendPacket(new SystemChatPacket(Component.text("second"), false));

        chat.assertSingle(packet -> assertEquals(Component.text("first"), packet.message()));
    }

    /**
     * With automatic component translation on, a tracked packet is rendered as a socket connection
     * renders it: through {@link EngineAdventure#COMPONENT_TRANSLATOR}, which a server may replace.
     * The switch is off by default, so this runs only with
     * {@code -Dsynaxis.automatic-component-translation=true} on the test JVM.
     */
    @Test
    void aTrackedPacketIsRenderedWithTheEngineTranslator(Env env) {
        assumeTrue(JvmFlags.AUTOMATIC_COMPONENT_TRANSLATION, "needs -Dsynaxis.automatic-component-translation=true");
        var connection = env.createConnection();
        var player = connection.connect(env.createFlatInstance(), new Pos(0, 42, 0));
        var chat = connection.trackIncoming(SystemChatPacket.class);
        var rendered = Component.text("rendered by the engine translator");

        var previous = EngineAdventure.COMPONENT_TRANSLATOR;
        EngineAdventure.COMPONENT_TRANSLATOR = (_, _) -> rendered;
        try {
            player.sendPacket(new SystemChatPacket(Component.translatable("probe.untranslated"), false));
        } finally {
            EngineAdventure.COMPONENT_TRANSLATOR = previous;
        }

        chat.assertSingle(packet -> assertEquals(rendered, packet.message()));
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
