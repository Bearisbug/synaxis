package io.github.bearisbug.synaxis.network.packet;

import io.github.bearisbug.synaxis.JvmFlags;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPluginMessagePacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginPluginRequestPacket;
import org.junit.jupiter.api.Test;

import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PacketWritingOversizedTest {

    private static ClientPluginMessagePacket packet(int payloadSize) {
        return new ClientPluginMessagePacket("synaxis:test", new byte[payloadSize]);
    }

    @Test
    public void aPacketBiggerThanTheScratchBufferIsRetriedOnAGrownOne() {
        final NetworkBuffer scratch = NetworkBuffer.staticBuffer(16);
        final var buffer = PacketWriting.allocateTrimmedPacket(scratch,
                PacketVanilla.CLIENT_PACKET_PARSER, ConnectionState.CONFIGURATION, packet(4096), 0);

        assertTrue(buffer.writeIndex() > 4096, "The whole payload has to be in the returned buffer");
        assertEquals(buffer.writeIndex(), buffer.capacity(), "The returned buffer is trimmed to its content");
    }

    @Test
    public void aPacketOverTheMaximumSizeIsRefusedInsteadOfGrowingForever() {
        // The scratch buffer must be the fixed-size kind the packet pool hands out: the oversize
        // guard is what runs once the first framing attempt overflows it
        final NetworkBuffer scratch = NetworkBuffer.staticBuffer(16);
        // The login plugin request has no cap on its payload, unlike the client-bound plugin message (1 MiB)
        final var oversized = new LoginPluginRequestPacket(1, "synaxis:test", new byte[JvmFlags.MAX_PACKET_SIZE + 1]);

        final var error = assertThrows(IllegalStateException.class,
                () -> PacketWriting.allocateTrimmedPacket(scratch,
                        PacketVanilla.SERVER_PACKET_PARSER, ConnectionState.LOGIN, oversized, 0));
        assertTrue(error.getMessage().startsWith("Packet too large: ")
                && error.getMessage().endsWith(" > " + JvmFlags.MAX_PACKET_SIZE + " (synaxis.max-packet-size)"),
                error.getMessage());
    }

    @Test
    public void aTrimmedPacketIsStillAReadableFrame() throws DataFormatException {
        final NetworkBuffer scratch = NetworkBuffer.staticBuffer(16);
        final var original = packet(2048);
        final var buffer = PacketWriting.allocateTrimmedPacket(scratch,
                PacketVanilla.CLIENT_PACKET_PARSER, ConnectionState.CONFIGURATION, original, 0);

        final var result = PacketReading.readClients(buffer, ConnectionState.CONFIGURATION, false);
        assertInstanceOf(PacketReading.Result.Success.class, result);
        final var success = (PacketReading.Result.Success<ClientPacket>) result;
        final var read = assertInstanceOf(ClientPluginMessagePacket.class, success.packets().getFirst().packet());
        assertEquals(original.channel(), read.channel());
        assertEquals(original.data().length, read.data().length);
    }

    @Test
    public void anUnregisteredPacketForTheStateIsRefusedBeforeAnyWrite() {
        final NetworkBuffer scratch = NetworkBuffer.staticBuffer(64);
        // A configuration-only packet has no id in the handshake registry
        assertThrows(IllegalStateException.class,
                () -> PacketWriting.allocateTrimmedPacket(scratch,
                        PacketVanilla.CLIENT_PACKET_PARSER, ConnectionState.HANDSHAKE, packet(4), 0));
        assertEquals(0, scratch.writeIndex(), "Nothing may be written before the id is known");
    }
}
