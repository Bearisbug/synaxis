package dev.gameengine.network.packet;

import dev.gameengine.network.packet.client.play.ClientDebugSubscriptionRequestPacket;
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
