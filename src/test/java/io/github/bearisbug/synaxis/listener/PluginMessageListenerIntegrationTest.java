package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent;
import io.github.bearisbug.synaxis.network.DisconnectReasons;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPluginMessagePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The channel of an inbound plugin message is checked before it is dispatched, as the key of a cookie
 * response is: the packet only bounds its length, and vanilla and Fabric clients only send identifiers.
 */
@EnvTest
class PluginMessageListenerIntegrationTest {

    @Test
    void aChannelThatIsNoIdentifierEndsTheConnectionWithoutBeingDispatched(Env env) {
        final TestConnection connection = env.createConnection();
        final Collector<DisconnectPacket> disconnects = connection.trackIncoming(DisconnectPacket.class);
        final Player player = connection.connect(env.createFlatInstance(), new Pos(0, 40, 0));
        final List<String> dispatched = new CopyOnWriteArrayList<>();
        env.process().eventHandler().addListener(PlayerPluginMessageEvent.class, event -> dispatched.add(event.getIdentifier()));

        player.addPacketToQueue(new ClientPluginMessagePacket("zz:a\n00:00:00.000 [Syn-TickScheduler] INFO", new byte[0]));
        player.interpretPacketQueue();

        assertEquals(List.of(), dispatched);
        assertFalse(player.getPlayerConnection().isOnline());
        disconnects.assertSingle(packet -> assertEquals(DisconnectReasons.PROTOCOL_ERROR, packet.message()));
    }

    @Test
    void anIdentifierIsDispatched(Env env) {
        final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 40, 0));
        final List<String> dispatched = new CopyOnWriteArrayList<>();
        env.process().eventHandler().addListener(PlayerPluginMessageEvent.class, event -> dispatched.add(event.getIdentifier()));

        player.addPacketToQueue(new ClientPluginMessagePacket("minecraft:brand", new byte[0]));
        player.addPacketToQueue(new ClientPluginMessagePacket("probe:game/v1", new byte[0]));
        // Without a namespace an identifier is in minecraft's
        player.addPacketToQueue(new ClientPluginMessagePacket("channel", new byte[0]));
        player.interpretPacketQueue();

        assertEquals(List.of("minecraft:brand", "probe:game/v1", "channel"), dispatched);
        assertTrue(player.getPlayerConnection().isOnline());
    }
}
