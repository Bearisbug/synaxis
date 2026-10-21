package io.github.bearisbug.synaxis.network.packet.server.common;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A client reads at most 32 report details: the packet refuses more where it is built, on the thread
 * of the caller, instead of the client disconnecting on it.
 */
public class CustomReportDetailsPacketTest {

    @Test
    public void thirtyTwoDetailsAreAccepted() {
        assertEquals(32, new CustomReportDetailsPacket(details(32)).details().size());
    }

    @Test
    public void thirtyThreeDetailsAreRefusedWithTheCountAndTheLimit() {
        final var error = assertThrows(IllegalArgumentException.class, () -> new CustomReportDetailsPacket(details(33)));
        assertTrue(error.getMessage().contains("33"), error.getMessage());
        assertTrue(error.getMessage().contains("32"), error.getMessage());
    }

    private static Map<String, String> details(int count) {
        final Map<String, String> details = new HashMap<>();
        for (int i = 0; i < count; i++) details.put("key" + i, "value" + i);
        return details;
    }
}
