package io.github.bearisbug.synaxis.network.player;

import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Packets sent to a connection after it disconnected, as a plugin that keeps a reference to a player
 * who left does.
 */
@EnvTest
public class OfflineSendIntegrationTest {

    /**
     * The write thread leaves once it has written what was queued before the disconnect: a packet
     * offered after that would stay in the queue for as long as the connection object lives, and
     * count towards a backlog that kicks a connection already gone.
     */
    @Test
    public void packetsSentAfterTheDisconnectAreDropped(Env env) throws Exception {
        try (OutboundBacklogIntegrationTest.Harness harness = new OutboundBacklogIntegrationTest.Harness(env)) {
            harness.connection.disconnect();
            final long backlog = harness.connection.backlogBytes();
            final long kicks = harness.kicks();

            // 10,000 × 16 KiB, far over the 4 MiB backlog limit
            for (int i = 0; i < 10_000; i++) harness.connection.sendPacket(harness.framed());

            assertEquals(backlog, harness.connection.backlogBytes(), "nothing more is queued");
            assertEquals(kicks, harness.kicks(), "a connection already gone is not kicked over its backlog");
        }
    }
}
