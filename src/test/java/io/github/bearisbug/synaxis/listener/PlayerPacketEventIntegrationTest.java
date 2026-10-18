package io.github.bearisbug.synaxis.listener;

import net.kyori.adventure.text.TranslatableComponent;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerPacketEvent;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientCookieResponsePacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientKeepAlivePacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPingRequestPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientConfigurationAckPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTickEndPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Which play packets {@link PlayerPacketEvent} is dispatched for: only those handled from the
 * player's queue, and never one that switches the decoding state.
 */
@EnvTest
public class PlayerPacketEventIntegrationTest {

    @Test
    public void aCancellingListenerCannotHoldBackAConfigurationAck(Env env) {
        final var testConnection = env.createConnection();
        final Player player = testConnection.connect(env.createFlatInstance(), new Pos(0, 40, 0));
        final var disconnects = testConnection.trackIncoming(DisconnectPacket.class);
        final PlayerConnection connection = player.getPlayerConnection();
        env.process().eventHandler().addListener(PlayerPacketEvent.class, event -> event.setCancelled(true));

        // Unsolicited, so the listener of the ack disconnects: it runs whatever the event listeners do
        player.addPacketToQueue(new ClientConfigurationAckPacket());
        player.interpretPacketQueue();

        assertFalse(connection.isOnline(), "the ack reached its listener");
        disconnects.assertSingle(disconnect -> assertEquals("synaxis:protocol-error",
                assertInstanceOf(TranslatableComponent.class, disconnect.message()).key()));
    }

    @Test
    public void theAnswersHandledOnTheReadThreadDispatchNoPacketEvent(Env env) {
        final var testConnection = env.createConnection();
        final Player player = testConnection.connect(env.createFlatInstance(), new Pos(0, 40, 0));
        final PlayerConnection connection = player.getPlayerConnection();
        assertEquals(ConnectionState.PLAY, connection.getClientState());
        final List<Class<?>> dispatched = new CopyOnWriteArrayList<>();
        env.process().eventHandler().addListener(PlayerPacketEvent.class,
                event -> dispatched.add(event.getPacket().getClass()));

        for (ClientPacket packet : List.of(new ClientPingRequestPacket(7),
                new ClientCookieResponsePacket("synaxis:probe", null),
                new ClientKeepAlivePacket(player.getLastKeepAlive()),
                new ClientTickEndPacket())) {
            env.process().packetListener().processClientPacket(packet, connection);
        }

        assertEquals(List.of(ClientTickEndPacket.class), dispatched);
    }
}
