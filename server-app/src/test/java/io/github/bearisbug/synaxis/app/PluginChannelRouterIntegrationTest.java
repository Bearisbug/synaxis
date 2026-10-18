package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which payloads the router hands to a handler ({@code docs/DESIGN-M2.md} {@code API-M2-001},
 * {@code REQ-M2-006}): a plugin's channel gets those of a connection in the play state only, a
 * channel of the host registered for the configuration gets them in both states, and the
 * {@code minecraft} namespace every client speaks is dropped without a line once nobody has the channel.
 */
@EnvTest
class PluginChannelRouterIntegrationTest {

    private static final byte[] PAYLOAD = "ping".getBytes(StandardCharsets.UTF_8);

    private record Routed(EventNode<Event> node, PluginChannelRouter router, Player player) {

        void send(String channel) {
            node.call(new PlayerPluginMessageEvent(player, channel, PAYLOAD));
        }
    }

    private static Routed routed(Env env) {
        final EventNode<Event> node = EventNode.all("router-probe");
        final PluginChannelRouter router = new PluginChannelRouter();
        router.attach(node);
        return new Routed(node, router, env.createPlayer(env.createFlatInstance(), new Pos(0, 41, 0)));
    }

    @Test
    void aPluginChannelGetsThePayloadsOfAConnectionInThePlayStateOnly(Env env) {
        final Routed routed = routed(env);
        final AtomicInteger handled = new AtomicInteger();
        routed.router().register("probe", "probe", (_, _) -> handled.incrementAndGet());
        final PlayerConnection connection = routed.player().getPlayerConnection();

        try (LogCapture logs = LogCapture.of(PluginChannelRouter.class)) {
            connection.setServerState(ConnectionState.CONFIGURATION);
            routed.send("probe:probe");
            assertEquals(0, handled.get(), "a plugin's handler got a payload of the configuration");
            final List<String> warnings = logs.messagesAt(Level.WARN);
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().startsWith("Dropped a payload outside the play state: connection_id="
                    + connection.connectionId() + " state=CONFIGURATION channel=probe:probe"), warnings.getFirst());

            connection.setServerState(ConnectionState.PLAY);
            routed.send("probe:probe");
            assertEquals(1, handled.get());
        }
    }

    @Test
    void aChannelOfTheHostRegisteredForTheConfigurationGetsItsPayloadsThen(Env env) {
        final Routed routed = routed(env);
        final AtomicInteger handled = new AtomicInteger();
        routed.router().register(PluginDescriptors.RESERVED_ID, "handshake", (_, _) -> handled.incrementAndGet(), true);
        routed.player().getPlayerConnection().setServerState(ConnectionState.CONFIGURATION);

        try (LogCapture logs = LogCapture.of(PluginChannelRouter.class)) {
            routed.send("synaxis:handshake");

            assertEquals(1, handled.get());
            assertEquals(List.of(), logs.messagesAt(Level.WARN));
        } finally {
            routed.player().getPlayerConnection().setServerState(ConnectionState.PLAY);
        }
    }

    @Test
    void theMinecraftNamespaceNobodyHasIsDroppedWithoutALine(Env env) {
        final Routed routed = routed(env);
        final PlayerConnection connection = routed.player().getPlayerConnection();

        try (LogCapture logs = LogCapture.of(PluginChannelRouter.class)) {
            routed.send("minecraft:brand");
            routed.send("minecraft:register");
            assertEquals(List.of(), logs.messagesAt(Level.WARN), "every vanilla and Fabric client sends these");

            routed.send("zz:game");
            final List<String> warnings = logs.messagesAt(Level.WARN);
            assertEquals(1, warnings.size(), warnings.toString());
            // The channel the client picked goes last, so that nothing in it can pose as a later field
            assertTrue(warnings.getFirst().startsWith("Dropped a payload on an unregistered channel: connection_id="
                    + connection.connectionId() + " bytes=4 channel=zz:game ("), warnings.getFirst());
        }
    }

    /** The silence comes after the lookup: a plugin may have the id {@code minecraft}. */
    @Test
    void aPluginWithTheIdMinecraftGetsItsChannels(Env env) {
        final Routed routed = routed(env);
        final AtomicInteger handled = new AtomicInteger();
        routed.router().register("minecraft", "probe", (_, _) -> handled.incrementAndGet());

        routed.send("minecraft:probe");

        assertEquals(1, handled.get());
    }
}
