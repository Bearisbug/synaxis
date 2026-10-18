package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.JvmFlags;
import io.github.bearisbug.synaxis.network.packet.PacketReading;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Malformed frame headers must be reported as protocol errors instead of leaking
 * internal exceptions to the read loop (issue #3320).
 */
class PacketReadingMalformedTest {

    @Test
    void zeroLengthFrameIsAProtocolError() {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(64);
        buffer.write(NetworkBuffer.VAR_INT, 0); // single 0x00 byte
        assertThrows(DataFormatException.class,
                () -> PacketReading.readClients(buffer, ConnectionState.HANDSHAKE, false));
    }

    /** A status exchange happens before any authentication, so it gets the smaller pre-auth budget. */
    @Test
    void statusFramesUseThePreAuthPacketSizeLimit() {
        assertEquals(JvmFlags.MAX_PACKET_SIZE_PRE_AUTH, PacketReading.maxPacketSize(ConnectionState.STATUS));

        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(64);
        buffer.write(NetworkBuffer.VAR_INT, JvmFlags.MAX_PACKET_SIZE_PRE_AUTH + 1);
        assertThrows(DataFormatException.class,
                () -> PacketReading.readClients(buffer, ConnectionState.STATUS, false));
    }

    @Test
    void overlongVarIntLengthIsAProtocolError() {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(64);
        for (int i = 0; i < 5; i++) buffer.write(NetworkBuffer.BYTE, (byte) 0xFF);
        assertThrows(DataFormatException.class,
                () -> PacketReading.readClients(buffer, ConnectionState.HANDSHAKE, false));
    }

    /**
     * A var-int read stops at the end of the segment, not at the bytes received: what lies past the
     * write index is left over from frames read earlier. A read that ends one or two bytes into the
     * length of a large frame has to wait for the rest, whatever those leftovers hold.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void aLengthCutShortByTheReadWaitsForTheRest(int received) throws DataFormatException {
        final NetworkBuffer buffer = NetworkBuffer.staticBuffer(64);
        // The first bytes of a length over 127, then leftovers that all carry the continuation bit
        for (int i = 0; i < received; i++) buffer.write(NetworkBuffer.BYTE, (byte) 0xAB);
        for (int i = received; i < 5; i++) buffer.write(NetworkBuffer.BYTE, (byte) 0xFF);
        buffer.writeIndex(received);

        final var result = PacketReading.readClients(buffer, ConnectionState.PLAY, false);
        assertInstanceOf(PacketReading.Result.Empty.class, result, "a length cut short is incomplete, not malformed");
        assertEquals(0, buffer.readIndex(), "nothing is taken off the buffer before the rest arrives");
    }

    /**
     * A compressed frame announces its inflated size up front, and that number is what the decoder
     * would size its output buffer by. An announcement over the packet size limit is refused on
     * the announcement alone, before anything is allocated or inflated.
     */
    @Test
    void aDeclaredDecompressedLengthOverTheLimitIsRefusedBeforeInflating() {
        final NetworkBuffer frame = NetworkBuffer.resizableBuffer(64);
        frame.write(NetworkBuffer.VAR_INT, JvmFlags.MAX_PACKET_SIZE + 1);
        frame.write(NetworkBuffer.RAW_BYTES, new byte[]{0x78, (byte) 0x9C}); // a zlib header, never read

        final DataFormatException refused = assertThrows(DataFormatException.class,
                () -> PacketReading.readClients(framed(frame), ConnectionState.PLAY, true));
        assertTrue(refused.getMessage().startsWith("Invalid decompressed length"),
                "the announcement itself must be what is refused, got: " + refused.getMessage());
    }

    /**
     * The announced size is also a ceiling: a stream that inflates past it is cut off at the
     * announcement and refused, so a peer cannot announce a small frame and inflate a large one.
     */
    @Test
    void aStreamInflatingPastItsDeclaredLengthIsAProtocolError() {
        final byte[] inflated = new byte[4096]; // zeros, deflate shrinks them to a few bytes
        final NetworkBuffer frame = NetworkBuffer.resizableBuffer(64);
        frame.write(NetworkBuffer.VAR_INT, 16); // announced far below what the stream holds
        frame.write(NetworkBuffer.RAW_BYTES, deflate(inflated));

        final DataFormatException refused = assertThrows(DataFormatException.class,
                () -> PacketReading.readClients(framed(frame), ConnectionState.PLAY, true));
        assertTrue(refused.getMessage().startsWith("Decompressed payload exceeds"),
                "the stream must be stopped at the announced size, got: " + refused.getMessage());
    }

    /** Wraps a compressed frame body into a frame, length prefixed like the wire has it. */
    private static NetworkBuffer framed(NetworkBuffer body) {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(64);
        buffer.write(NetworkBuffer.VAR_INT, (int) body.writeIndex());
        buffer.write(NetworkBuffer.RAW_BYTES, body.read(NetworkBuffer.RAW_BYTES));
        return buffer;
    }

    private static byte[] deflate(byte[] bytes) {
        final Deflater deflater = new Deflater();
        deflater.setInput(bytes);
        deflater.finish();
        final byte[] out = new byte[bytes.length + 64];
        final int length = deflater.deflate(out);
        deflater.end();
        return Arrays.copyOf(out, length);
    }

    @Test
    void truncatedVarIntLengthAtBufferEndRequiresABiggerBuffer() throws DataFormatException {
        // Only a partial var-int fits before the end of the segment, the caller must be
        // asked for a capacity strictly greater than the current one.
        final NetworkBuffer buffer = NetworkBuffer.staticBuffer(8);
        buffer.writeIndex(4).readIndex(4);
        for (int i = 0; i < 4; i++) buffer.write(NetworkBuffer.BYTE, (byte) 0xFF);

        final var result = PacketReading.readClients(buffer, ConnectionState.HANDSHAKE, false);
        final var failure = assertInstanceOf(PacketReading.Result.Failure.class, result);
        assertEquals(4 + 5, failure.requiredCapacity());
    }
}
