package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.network.packet.PacketReading;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnvTest
public class PacketCompressionIntegrationTest {

    @BeforeAll
    public static void setup(Env env) { // PACKET_POOL
        assertNotNull(env.process().registries());
    }

    @Test
    public void compressRejectsAnOutputTooSmallForTheStream() {
        final byte[] random = new byte[1 << 16];
        new Random(42).nextBytes(random); // incompressible, deflate will grow it
        final NetworkBuffer input = NetworkBuffer.staticBuffer(random.length);
        input.write(NetworkBuffer.RAW_BYTES, random);

        final NetworkBuffer output = NetworkBuffer.staticBuffer(random.length);
        assertThrows(IndexOutOfBoundsException.class, () -> input.compress(0, random.length, output));
        assertEquals(0, output.writeIndex(), "a truncated deflate stream must not be published");
    }

    @Test
    public void incompressiblePacketSurvivesTheCompressedFrame(Env env) throws DataFormatException {
        final byte[] payload = new byte[200_000];
        new Random(42).nextBytes(payload);
        final PluginMessagePacket packet = new PluginMessagePacket("ch", payload);

        final NetworkBuffer framed = PacketWriting.allocateTrimmedPacket(ConnectionState.PLAY, packet,
                env.process().registries(), 256);
        final var result = PacketReading.readServers(framed, ConnectionState.PLAY, true);

        if (!(result instanceof PacketReading.Result.Success<ServerPacket>(
                List<PacketReading.ParsedPacket<ServerPacket>> parsed
        ))) {
            throw new AssertionError("Expected a success result, got " + result);
        }
        assertEquals(List.<ServerPacket>of(packet),
                parsed.stream().map(PacketReading.ParsedPacket::packet).toList());
    }
}
