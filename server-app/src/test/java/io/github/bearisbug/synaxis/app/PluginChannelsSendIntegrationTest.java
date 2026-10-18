package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.plugin.PluginChannels;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A payload over what a client accepts used to be sent all the same: every client receiving it
 * disconnected, with nothing in the log of the server. The plugin gets the refusal on its own thread.
 */
@EnvTest
public class PluginChannelsSendIntegrationTest {

    @Test
    public void aPayloadOverOneMebibyteIsRefusedOnTheCallingThread(Env env) {
        var connection = env.createConnection();
        Player player = connection.connect(env.createFlatInstance(), new Pos(0, 40, 0));
        var messages = connection.trackIncoming(PluginMessagePacket.class);
        final PluginChannels channels = new PluginChannelsImpl("probe", new PluginChannelRouter());

        final var error = assertThrows(IllegalArgumentException.class,
                () -> channels.send(player, "big", new byte[1_048_577]));
        assertTrue(error.getMessage().contains("probe:big"), error.getMessage());
        messages.assertEmpty();
        assertTrue(player.isOnline(), "the refusal must not cost the player its connection");

        var delivered = connection.trackIncoming(PluginMessagePacket.class); // A collector stops once read
        channels.send(player, "big", new byte[1_048_576]);
        delivered.assertSingle(packet -> assertEquals(1_048_576, packet.data().length));
    }
}
