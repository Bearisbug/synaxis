package io.github.bearisbug.synaxis.network.player;

import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerPreLoginEvent;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.network.socket.Server;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.SocketChannel;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A peer that stops reading is the cheapest way to hold on to a server socket: a blocking socket
 * write only returns once the kernel takes the bytes, and the kernel only takes them once the peer
 * reads. The engine's answer to a peer that went quiet is a kick, and the packet of that kick is
 * written by the very thread stuck in that write.
 */
@EnvTest
public class SlowReaderIntegrationTest {

    /** More than the send buffer of the server and the receive buffer of the peer hold together. */
    private static final int QUEUED_BYTES = 16 << 20;

    /**
     * Once a connection is disconnected, its channel has to be closed whether or not the peer ever
     * reads again: the write thread cannot do it from inside the write, and without it the channel,
     * both socket threads, the read buffer and everything queued for the peer stay for as long as
     * the peer keeps the TCP connection up.
     */
    @Test
    public void aDisconnectClosesTheChannelUnderAWriteThePeerNeverReads(Env env) throws Exception {
        final Server server = env.process().server();
        server.init(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        server.start();
        final CompletableFuture<PlayerSocketConnection> accepted = new CompletableFuture<>();
        env.process().eventHandler().addListener(AsyncPlayerPreLoginEvent.class,
                event -> accepted.complete((PlayerSocketConnection) event.getConnection()));
        try (SocketChannel peer = SocketChannel.open()) {
            peer.setOption(StandardSocketOptions.SO_RCVBUF, 4096);
            peer.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()));
            write(peer, ConnectionState.HANDSHAKE, new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION,
                    "localhost", server.getPort(), ClientHandshakePacket.Intent.LOGIN));
            write(peer, ConnectionState.LOGIN, new ClientLoginStartPacket("slow", UUID.randomUUID()));
            final PlayerSocketConnection connection = accepted.get(10, TimeUnit.SECONDS);
            // The login success moves the server state on once it is written; only then does the
            // configuration registry, which holds the plugin message, apply to the queue
            awaitServerState(connection, ConnectionState.CONFIGURATION);

            // Incompressible, so that the compression the login switched on does not shrink it
            final byte[] noise = new byte[16_000];
            new Random(7).nextBytes(noise);
            for (int queued = 0; queued < QUEUED_BYTES; queued += noise.length)
                connection.sendPacket(new PluginMessagePacket("synaxis:noise", noise));
            // Give the write thread the time to fill the socket buffers and block
            Thread.sleep(500);
            assertTrue(connection.writeThread().isAlive(), "the write thread is still flushing");

            connection.disconnect();

            connection.writeThread().join(5_000);
            connection.readThread().join(5_000);
            assertFalse(connection.getChannel().isOpen(),
                    "a disconnected connection must close its channel even while the peer is not reading");
            assertFalse(connection.writeThread().isAlive(), "the write thread must not outlive the connection");
            assertFalse(connection.readThread().isAlive(), "the read thread must not outlive the connection");
        } finally {
            server.stop();
        }
    }

    private static void write(SocketChannel peer, ConnectionState state, ClientPacket packet) throws IOException {
        final NetworkBuffer framed = PacketWriting.allocateTrimmedPacket(state, packet, null, 0);
        while (!framed.writeChannel(peer)) {
            // A blocking channel writes everything, the loop only documents the contract
        }
    }

    private static void awaitServerState(PlayerSocketConnection connection, ConnectionState state)
            throws InterruptedException {
        for (int i = 0; i < 100 && connection.getServerState() != state; i++) Thread.sleep(50);
        assertTrue(connection.getServerState() == state, "the login never reached " + state);
    }
}
