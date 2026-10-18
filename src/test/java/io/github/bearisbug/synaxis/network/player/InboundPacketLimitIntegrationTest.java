package io.github.bearisbug.synaxis.network.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerPacketEvent;
import io.github.bearisbug.synaxis.event.player.PlayerPacketOutEvent;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.PacketVanilla;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPingRequestPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPluginMessagePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTabCompletePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTickEndPacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PingResponsePacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import net.kyori.adventure.text.TranslatableComponent;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inbound packet limits on a real socket ({@code docs/DESIGN-PROD.md} {@code API-PROD-012},
 * §19 「入站包超速被踢」「解压字节超速被踢」「补全限频」), with the engine defaults: 750 packets in
 * 7 s, 4 MiB inflated per second, 20 tab completions per second.
 */
@EnvTest
public class InboundPacketLimitIntegrationTest {

    @Test
    public void oneOverTheLimitKicksWithProtocolError(Env env) throws IOException {
        try (Harness harness = new Harness(env, false)) {
            final List<ClientPacket> packets = new ArrayList<>();
            for (int i = 0; i < 751; i++) packets.add(new ClientTickEndPacket());
            harness.send(packets, 0);

            assertFalse(harness.connection.isOnline(), "751 packets at once are over 750 in 7 s");
            assertEquals("synaxis:protocol-error", harness.disconnectKey());
        }
    }

    @Test
    public void theLimitItselfIsLetThrough(Env env) throws IOException {
        try (Harness harness = new Harness(env, false)) {
            final List<ClientPacket> packets = new ArrayList<>();
            for (int i = 0; i < 750; i++) packets.add(new ClientTickEndPacket());
            harness.send(packets, 0);

            assertTrue(harness.connection.isOnline(), "750 packets in 7 s is the limit, not over it");
            assertEquals(750, harness.drain(ClientTickEndPacket.class));
        }
    }

    /**
     * A ping in the play state is answered right away, not queued, and still counts towards the
     * limit: a peer cannot get a pong per packet past it ({@code docs/TEST.md} TC-PROD-584).
     */
    @Test
    public void pingsInPlayCountTowardsTheLimit(Env env) throws IOException {
        try (Harness harness = new Harness(env, false)) {
            harness.send(pings(1_000), 0);

            assertFalse(harness.connection.isOnline(), "1,000 pings at once are over 750 in 7 s");
            assertEquals("synaxis:protocol-error", harness.disconnectKey());
            assertEquals(750, harness.sent(PingResponsePacket.class), "pongs");
        }
    }

    /**
     * With {@code synaxis.packet-limit.action=DROP} a ping over the limit is thrown away before
     * its listener, like a queued packet: no pong, and the connection stays
     * ({@code docs/TEST.md} TC-PROD-584).
     */
    @Test
    public void pingsOverADropLimitAreNotAnswered() throws IOException {
        try (Env env = Env.create(settings -> settings.packetLimitAction(PacketLimitAction.DROP));
             Harness harness = new Harness(env, false)) {
            harness.send(pings(1_000), 0);
            harness.connection.flushSync();

            assertTrue(harness.connection.isOnline(), "a dropped ping does not kick");
            assertEquals(750, harness.sent(PingResponsePacket.class), "pongs");
        }
    }

    @Test
    public void inflatingMoreThanFourMebibytesInOneSecondKicks(Env env) throws IOException {
        try (Harness harness = new Harness(env, true)) {
            // 150 frames that inflate to 30,000 bytes each, 4,500,000 bytes > 4 MiB (4,194,304)
            harness.send(pluginMessages(150), 256);

            assertFalse(harness.connection.isOnline());
            assertEquals("synaxis:protocol-error", harness.disconnectKey());
        }
    }

    @Test
    public void inflatingThreeMegabytesInOneSecondIsFine(Env env) throws IOException {
        try (Harness harness = new Harness(env, true)) {
            harness.send(pluginMessages(100), 256);

            assertTrue(harness.connection.isOnline(), "100 frames of 30,000 bytes are 3,000,000 bytes, under 4 MiB");
            assertEquals(100, harness.drain(ClientPluginMessagePacket.class));
        }
    }

    @Test
    public void tabCompletionsOverTwentyInOneSecondAreDropped(Env env) throws IOException {
        try (Harness harness = new Harness(env, false)) {
            final List<ClientPacket> packets = new ArrayList<>();
            for (int i = 0; i < 100; i++) packets.add(new ClientTabCompletePacket(i, "/kick Ru"));
            harness.send(packets, 0);

            assertTrue(harness.connection.isOnline(), "a dropped completion does not kick");
            assertEquals(20, harness.drain(ClientTabCompletePacket.class));
        }
    }

    private static List<ClientPacket> pings(int count) {
        final List<ClientPacket> packets = new ArrayList<>();
        for (int i = 0; i < count; i++) packets.add(new ClientPingRequestPacket(i));
        return packets;
    }

    private static List<ClientPacket> pluginMessages(int count) {
        final List<ClientPacket> packets = new ArrayList<>();
        for (int i = 0; i < count; i++) packets.add(new ClientPluginMessagePacket("synaxis:inflate", new byte[30_000]));
        return packets;
    }

    /** A connection in the play state with a player, read on the test thread. */
    private static final class Harness implements AutoCloseable {
        private final Env env;
        private final ServerSocketChannel server;
        private final SocketChannel peer;
        private final SocketChannel accepted;
        final PlayerSocketConnection connection;
        private final Player player;
        private final List<ServerPacket> sent = new ArrayList<>();

        Harness(Env env, boolean compressed) throws IOException {
            this.env = env;
            this.server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            this.peer = SocketChannel.open(server.getLocalAddress());
            this.accepted = server.accept();
            this.connection = new PlayerSocketConnection(env.process(), accepted, accepted.getRemoteAddress(),
                    Thread.currentThread(), Thread.currentThread());
            if (compressed) {
                // The login state is the one that announces compression
                connection.setServerState(ConnectionState.LOGIN);
                connection.startCompression();
                connection.flushSync();
            }
            connection.setClientState(ConnectionState.PLAY);
            connection.setServerState(ConnectionState.PLAY);
            this.player = new Player(connection, new GameProfile(UUID.randomUUID(), "Flooder"));
            connection.setPlayer(player);
            env.process().eventHandler().addListener(PlayerPacketOutEvent.class, event -> sent.add(event.getPacket()));
        }

        /** Writes the frames, closes the peer's output and reads until the end of the stream. */
        void send(List<ClientPacket> packets, int compressionThreshold) throws IOException {
            final NetworkBuffer frames = NetworkBuffer.resizableBuffer();
            for (ClientPacket packet : packets)
                PacketWriting.writeFramedPacket(frames, ConnectionState.PLAY, packet, compressionThreshold);
            while (!frames.writeChannel(peer)) {
                // A blocking channel writes everything
            }
            peer.shutdownOutput();
            try {
                while (connection.isOnline()) connection.read(PacketVanilla.CLIENT_PACKET_PARSER);
            } catch (EOFException _) {
                // Everything was read
            }
        }

        /** How many packets of a type made it to the player's queue. */
        int drain(Class<? extends ClientPacket> type) {
            final AtomicInteger count = new AtomicInteger();
            env.process().eventHandler().addListener(PlayerPacketEvent.class, event -> {
                if (type.isInstance(event.getPacket())) count.incrementAndGet();
                event.setCancelled(true);
            });
            for (int i = 0; i < 100; i++) player.interpretPacketQueue();
            return count.get();
        }

        String disconnectKey() throws IOException {
            connection.flushSync();
            final List<DisconnectPacket> disconnects = sent.stream()
                    .filter(DisconnectPacket.class::isInstance).map(DisconnectPacket.class::cast).toList();
            assertEquals(1, disconnects.size(), "one disconnect packet");
            return assertInstanceOf(TranslatableComponent.class, disconnects.getFirst().message()).key();
        }

        /** How many packets of a type were written to the peer so far. */
        long sent(Class<? extends ServerPacket> type) {
            return sent.stream().filter(type::isInstance).count();
        }

        @Override
        public void close() throws IOException {
            peer.close();
            accepted.close();
            server.close();
            // The pooled buffers are shared by the whole JVM and this test grows some of them past
            // pooledBufferSize, which other tests size their packets by
            PacketVanilla.PACKET_POOL.clear();
        }
    }
}
