package dev.gameengine.registry;

import dev.gameengine.network.ConnectionState;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.packet.PacketRegistry;
import dev.gameengine.network.packet.PacketVanilla;
import dev.gameengine.network.packet.server.SendablePacket;
import dev.gameengine.network.packet.server.ServerPacket;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Guards the configuration phase registry data against any change in registry order, entry order or entry names.
 * The reference file was recorded from a real client session before the opaque registry conversion, see
 * {@code docs/TEST.md} TC-007.
 */
@EnvTest
class RegistryDataByteParityIntegrationTest {
    private static final String BASELINE = "/protocol/registry-data-26.2.bin";

    @Test
    void registryDataMatchesRecordedBaseline(Env env) throws IOException {
        final Registries registries = env.process();
        final PacketRegistry<ServerPacket.Configuration> packets = PacketVanilla.SERVER_PACKET_PARSER.configuration();
        final byte[] actual = NetworkBuffer.makeArray(buffer -> {
            for (SendablePacket sendable : Registries.registryDataPackets(registries, true)) {
                final ServerPacket.Configuration packet = (ServerPacket.Configuration)
                        SendablePacket.extractServerPacket(ConnectionState.CONFIGURATION, sendable);
                final PacketRegistry.PacketInfo<ServerPacket.Configuration> info = packets.packetInfo(packet);
                buffer.write(NetworkBuffer.VAR_INT, info.id());
                buffer.write(info.serializer(), packet);
            }
        }, registries);

        final byte[] expected;
        try (InputStream stream = RegistryDataByteParityIntegrationTest.class.getResourceAsStream(BASELINE)) {
            expected = Objects.requireNonNull(stream, BASELINE).readAllBytes();
        }
        assertArrayEquals(expected, actual);
    }
}
