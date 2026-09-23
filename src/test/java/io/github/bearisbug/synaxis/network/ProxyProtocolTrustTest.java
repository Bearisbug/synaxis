package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.status.StatusRequestPacket;
import io.github.bearisbug.synaxis.network.socket.Server;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PROXY protocol behind a load balancer ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-036}): a
 * header names any address its sender likes, so a process only switches it on together with the
 * networks allowed to send one, and a header from anywhere else ends the connection.
 */
class ProxyProtocolTrustTest {

    @Test
    void theProxyProtocolIsRefusedWithoutTrustedNetworks() {
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().proxyProtocol(true).proxyProtocolRequired(true).build());
        assertTrue(refused.getMessage().contains("synaxis.proxy-protocol.trusted-networks"), refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().proxyProtocol(true)
                .proxyProtocolRequired(true).proxyProtocolTrustedNetworks(" , ").build(), "a list of nothing");
    }

    @Test
    void theProxyProtocolIsRefusedWhenTheHeaderIsOptional() {
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().proxyProtocol(true).proxyProtocolTrustedNetworks("10.0.0.0/8").build());
        assertTrue(refused.getMessage().contains("synaxis.proxy-protocol.required"), refused.getMessage());
    }

    @Test
    void theTrustedNetworksAreCheckedLikeTheGateOnes() {
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().proxyProtocolTrustedNetworks("lb.example.com").build());
        assertTrue(refused.getMessage().contains("synaxis.proxy-protocol.trusted-networks"), refused.getMessage());
        assertDoesNotThrow(() -> ServerConfig.builder().proxyProtocol(true).proxyProtocolRequired(true)
                .proxyProtocolTrustedNetworks("10.0.0.0/8, fd00::/8").build());
        assertDoesNotThrow(() -> ServerConfig.builder().build(), "without the PROXY protocol nothing is needed");
    }

    @Test
    void aHeaderFromOutsideTheTrustedNetworksIsDisconnected() throws IOException {
        final ServerProcess process = start("10.0.0.0/8");
        try (SocketChannel peer = connect(process)) {
            peer.write(ByteBuffer.wrap(proxyHeader("203.0.113.7")));
            statusRequest(process, peer);
            assertFalse(answered(peer), "127.0.0.1 is not a load balancer of 10.0.0.0/8");
        } finally {
            process.stop();
        }
    }

    @Test
    void aHeaderFromATrustedNetworkIsServed() throws IOException {
        final ServerProcess process = start("10.0.0.0/8, 127.0.0.0/8");
        try (SocketChannel peer = connect(process)) {
            peer.write(ByteBuffer.wrap(proxyHeader("203.0.113.7")));
            statusRequest(process, peer);
            assertTrue(answered(peer), "a load balancer in the trusted networks gets the status response");
        } finally {
            process.stop();
        }
    }

    @Test
    void aConnectionWithoutTheHeaderIsDisconnected() throws IOException {
        final ServerProcess process = start("127.0.0.0/8");
        try (SocketChannel peer = connect(process)) {
            statusRequest(process, peer);
            assertFalse(answered(peer), "the header is required, a direct connection is not served");
        } finally {
            process.stop();
        }
    }

    private static ServerProcess start(String trustedNetworks) throws IOException {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().insideTest(true)
                .proxyProtocol(true).proxyProtocolRequired(true).proxyProtocolTrustedNetworks(trustedNetworks)
                .connectionGateTrustedNetworks("0.0.0.0/0").build());
        final Server server = process.server();
        server.init(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        server.start();
        return process;
    }

    private static SocketChannel connect(ServerProcess process) throws IOException {
        final SocketChannel channel = SocketChannel.open(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), process.server().getPort()));
        channel.socket().setSoTimeout(5_000);
        return channel;
    }

    private static byte[] proxyHeader(String client) {
        return ("PROXY TCP4 " + client + " 127.0.0.1 40000 25565\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static void statusRequest(ServerProcess process, SocketChannel peer) throws IOException {
        send(peer, ConnectionState.HANDSHAKE, new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION,
                "localhost", process.server().getPort(), ClientHandshakePacket.Intent.STATUS));
        send(peer, ConnectionState.STATUS, new StatusRequestPacket());
    }

    private static void send(SocketChannel peer, ConnectionState state, ClientPacket packet) throws IOException {
        final NetworkBuffer framed = PacketWriting.allocateTrimmedPacket(state, packet, null, 0);
        while (!framed.writeChannel(peer)) {
            // A blocking channel writes everything, the loop only documents the contract
        }
    }

    /** Whether the server wrote anything before closing; a server that does neither fails the test. */
    private static boolean answered(SocketChannel peer) throws IOException {
        try {
            return peer.socket().getInputStream().read() >= 0;
        } catch (SocketTimeoutException e) {
            throw new AssertionError("the server neither answered nor closed the connection", e);
        } catch (IOException _) {
            return false; // Reset by the server, as good as closed
        }
    }
}
