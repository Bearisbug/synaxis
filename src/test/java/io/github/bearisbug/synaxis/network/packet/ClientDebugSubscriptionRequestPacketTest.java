package io.github.bearisbug.synaxis.network.packet;

import io.github.bearisbug.synaxis.network.packet.client.play.ClientDebugSubscriptionRequestPacket;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class ClientDebugSubscriptionRequestPacketTest {

    @Test
    void testUnmodifiable() {
        var packet = new ClientDebugSubscriptionRequestPacket(new HashSet<>());
        assertThrows(UnsupportedOperationException.class, () -> packet.subscriptionIds().add(1));
    }
}
