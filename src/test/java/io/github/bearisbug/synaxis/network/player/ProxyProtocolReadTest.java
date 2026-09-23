package io.github.bearisbug.synaxis.network.player;

import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.PacketVanilla;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The PROXY protocol header is the only thing the read loop consumes outside the packet reader, and
 * the handshake behind it shares the buffer the header was read into.
 */
class ProxyProtocolReadTest {
    private static final int CAPACITY = ServerConfig.DEFAULT_POOLED_BUFFER_SIZE;
    // A buffer left with nowhere to write reads zero bytes forever, the bound makes that a failed
    // assertion instead of a spinning read loop
    private static final int READ_ATTEMPTS = 64;

    @Test
    void readsTheHandshakeBehindAHeaderFillingTheBuffer() throws IOException {
        assertEquals(ConnectionState.LOGIN, handshakeStateBehind(v2LocalHeader(CAPACITY)),
                "a header the size of the read buffer must still be followed by its handshake");
    }

    @Test
    void readsAHandshakeTheHeaderLeftNoRoomFor() throws IOException {
        // The header leaves two bytes of the handshake in the buffer and nowhere to write the rest
        assertEquals(ConnectionState.LOGIN, handshakeStateBehind(v2LocalHeader(CAPACITY - 2)),
                "a handshake cut in two by the end of the read buffer must still be read");
    }

    /**
     * Feeds one PROXY header and one handshake to a real connection and returns the state the
     * handshake left it in. The peer closes its side and the read loop is bounded, so a header that
     * left the buffer unusable fails the assertion rather than hanging the test.
     */
    private static ConnectionState handshakeStateBehind(byte[] header) throws IOException {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder()
                .insideTest(true)
                .proxyProtocol(true)
                .proxyProtocolRequired(true)
                .proxyProtocolTrustedNetworks("127.0.0.0/8")
                .build());
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (SocketChannel peer = SocketChannel.open(server.getLocalAddress());
                 SocketChannel accepted = server.accept()) {
                final PlayerSocketConnection connection = new PlayerSocketConnection(process, accepted,
                        accepted.getRemoteAddress(), Thread.currentThread(), Thread.currentThread());
                peer.write(ByteBuffer.wrap(header));
                peer.write(ByteBuffer.wrap(handshakeFrame()));
                peer.shutdownOutput();

                for (int i = 0; i < READ_ATTEMPTS && connection.isOnline(); i++) {
                    try {
                        connection.read(PacketVanilla.CLIENT_PACKET_PARSER);
                    } catch (EOFException _) {
                        break; // Everything the peer sent has been read
                    }
                }
                return connection.getClientState();
            }
        }
    }

    /** A v2 LOCAL header of the requested total length, the shortest header a proxy can send. */
    private static byte[] v2LocalHeader(int headerLength) {
        final byte[] bytes = new byte[headerLength];
        final byte[] signature = {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        bytes[12] = 0x20; // version 2, LOCAL command
        bytes[13] = 0x00; // unspecified family and protocol
        final int payloadLength = headerLength - signature.length - 4;
        bytes[14] = (byte) (payloadLength >>> 8);
        bytes[15] = (byte) payloadLength;
        return bytes;
    }

    private static byte[] handshakeFrame() {
        final byte[] payload = NetworkBuffer.makeArray(buffer -> {
            buffer.write(NetworkBuffer.VAR_INT, 0); // the handshake is the only packet of its state
            buffer.write(ClientHandshakePacket.SERIALIZER, new ClientHandshakePacket(
                    MinecraftServer.PROTOCOL_VERSION, "localhost", 25565, ClientHandshakePacket.Intent.LOGIN));
        });
        return NetworkBuffer.makeArray(buffer -> {
            buffer.write(NetworkBuffer.VAR_INT, payload.length);
            buffer.write(NetworkBuffer.RAW_BYTES, payload);
        });
    }
}
