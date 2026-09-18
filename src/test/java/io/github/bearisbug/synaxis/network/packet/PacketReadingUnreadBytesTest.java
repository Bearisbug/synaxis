package io.github.bearisbug.synaxis.network.packet;

import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A client decides how many packets it sends and what it leaves in their tails, so the "not fully
 * read" report must not be written once per packet.
 * <p>
 * The counters {@link PacketReading} throttles on are process wide, so this class runs alone.
 */
@Isolated
public class PacketReadingUnreadBytesTest {

    /** Bytes appended past the payload of a {@code StatusRequestPacket}, which reads nothing. */
    private static final int TRAILING_BYTES = 4;

    @Test
    public void aPacketWithTrailingBytesIsReportedAtMostOncePerInterval() throws DataFormatException {
        PacketReading.unreadBytesPackets.set(0);
        PacketReading.lastUnreadBytesReport.set(System.nanoTime() - PacketReading.UNREAD_BYTES_REPORT_INTERVAL);

        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(32);

        // The first packet is the one that gets written, draining the pending counter
        readPaddedStatusRequest(buffer);
        assertEquals(0, PacketReading.unreadBytesPackets.get(),
                "the first packet was not reported");

        final int flood = 10_000;
        for (int i = 0; i < flood; i++) readPaddedStatusRequest(buffer);
        assertEquals(flood, PacketReading.unreadBytesPackets.get(),
                "a line per packet would put the size of the log under the client's control");
    }

    /** Feeds one status request frame padded with bytes the packet does not read. */
    private static void readPaddedStatusRequest(NetworkBuffer buffer) throws DataFormatException {
        buffer.clear();
        buffer.write(NetworkBuffer.VAR_INT, 1 + TRAILING_BYTES); // frame length: packet id plus the padding
        buffer.write(NetworkBuffer.VAR_INT, 0); // StatusRequestPacket
        for (int i = 0; i < TRAILING_BYTES; i++) buffer.write(NetworkBuffer.BYTE, (byte) 0);

        assertInstanceOf(PacketReading.Result.Success.class,
                PacketReading.readClient(buffer, ConnectionState.STATUS, false));
    }
}
