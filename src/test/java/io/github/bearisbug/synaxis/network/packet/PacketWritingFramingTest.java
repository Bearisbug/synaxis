package io.github.bearisbug.synaxis.network.packet;

import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPluginMessagePacket;
import org.junit.jupiter.api.Test;

import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PacketWritingFramingTest {

    private static ClientPluginMessagePacket packet(int payloadSize) {
        return new ClientPluginMessagePacket("synaxis:test", new byte[payloadSize]);
    }

    private static ClientPluginMessagePacket readBack(NetworkBuffer buffer, boolean compressed)
            throws DataFormatException {
        final var result = PacketReading.readClients(buffer, ConnectionState.CONFIGURATION, compressed);
        assertInstanceOf(PacketReading.Result.Success.class, result);
        final var success = (PacketReading.Result.Success<ClientPacket>) result;
        assertEquals(1, success.packets().size());
        return assertInstanceOf(ClientPluginMessagePacket.class, success.packets().getFirst().packet());
    }

    @Test
    public void anUncompressedFrameStartsWithItsOwnLength() throws DataFormatException {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(256);
        PacketWriting.writeFramedPacket(buffer, ConnectionState.CONFIGURATION, packet(4), 0);

        final long total = buffer.writeIndex();
        final int length = buffer.read(NetworkBuffer.VAR_INT);
        assertEquals(total - 3, length, "The length header is always written on three bytes");

        buffer.readIndex(0);
        assertEquals(12, readBack(buffer, false).channel().length());
    }

    @Test
    public void aFrameBelowTheCompressionThresholdKeepsAZeroUncompressedLength() throws DataFormatException {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(256);
        PacketWriting.writeFramedPacket(buffer, ConnectionState.CONFIGURATION, packet(4), 1024);

        buffer.read(NetworkBuffer.VAR_INT); // packet length
        assertEquals(0, buffer.read(NetworkBuffer.VAR_INT),
                "A payload below the threshold is sent as is, marked by a zero data length");

        buffer.readIndex(0);
        assertEquals(4, readBack(buffer, true).data().length);
    }

    @Test
    public void aFrameAboveTheCompressionThresholdCarriesItsUncompressedLength() throws DataFormatException {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(4096);
        final var original = packet(1024);
        PacketWriting.writeFramedPacket(buffer, ConnectionState.CONFIGURATION, original, 64);

        buffer.read(NetworkBuffer.VAR_INT); // packet length
        final int uncompressedLength = buffer.read(NetworkBuffer.VAR_INT);
        assertTrue(uncompressedLength > 1024, "The data length is the size of the whole uncompressed payload");

        buffer.readIndex(0);
        final var read = readBack(buffer, true);
        assertEquals(original.channel(), read.channel());
        assertEquals(original.data().length, read.data().length);
    }

    @Test
    public void aRoundTripKeepsThePayloadOfEveryFramingMode() throws DataFormatException {
        for (int threshold : new int[]{0, 1, 256}) {
            final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(4096);
            final var original = packet(128);
            PacketWriting.writeFramedPacket(buffer, ConnectionState.CONFIGURATION, original, threshold);

            final var read = readBack(buffer, threshold > 0);
            assertEquals(original.channel(), read.channel());
            assertEquals(original.data().length, read.data().length);
        }
    }
}
