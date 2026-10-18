package io.github.bearisbug.synaxis.network.packet.server.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A vanilla client accepts a client-bound custom payload of at most 1 MiB and disconnects on a larger
 * one: the packet refuses it where it is built, on the thread of the caller.
 */
public class PluginMessagePacketTest {

    @Test
    public void aPayloadOfOneMebibyteIsAccepted() {
        assertDoesNotThrow(() -> new PluginMessagePacket("probe:big", new byte[1_048_576]));
    }

    @Test
    public void aPayloadOverOneMebibyteIsRefusedWithItsChannelAndLength() {
        final var error = assertThrows(IllegalArgumentException.class,
                () -> new PluginMessagePacket("probe:big", new byte[1_048_577]));
        assertTrue(error.getMessage().contains("probe:big"), error.getMessage());
        assertTrue(error.getMessage().contains("1048577"), error.getMessage());
    }
}
