package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The width of a color patch is an unsigned byte, 0 meaning no patch: a patch of no columns cannot be
 * written, and a patch as wide as the whole map, 128 columns, reads back.
 */
public class MapDataPacketTest {

    @Test
    public void aPatchOfNoColumnsIsRefused() {
        final var error = assertThrows(IllegalArgumentException.class,
                () -> new MapDataPacket.ColorContent((byte) 0, (byte) 1, (byte) 0, (byte) 0, new byte[0]));
        assertEquals("ColorContent columns must be 1..128, pass a null colorContent for no color patch", error.getMessage());
    }

    @Test
    public void aPatchOfTheWholeMapReadsBack() {
        final byte[] colors = new byte[128 * 128];
        for (int i = 0; i < colors.length; i++) colors[i] = (byte) i;
        final MapDataPacket packet = new MapDataPacket(5, (byte) 1, true, false, List.of(),
                new MapDataPacket.ColorContent((byte) 128, (byte) 128, (byte) 0, (byte) 0, colors));
        final byte[] wire = NetworkBuffer.makeArray(MapDataPacket.SERIALIZER, packet);
        final NetworkBuffer buffer = NetworkBuffer.wrap(wire, 0, wire.length);

        assertEquals(packet, buffer.read(MapDataPacket.SERIALIZER));
        assertEquals(0, buffer.readableBytes());
    }
}
