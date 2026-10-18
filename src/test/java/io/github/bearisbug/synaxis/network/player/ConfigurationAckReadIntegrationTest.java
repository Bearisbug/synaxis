package io.github.bearisbug.synaxis.network.player;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.PacketVanilla;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientKeepAlivePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientConfigurationAckPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The configuration acknowledgement changes the state the packets behind it are decoded in, so the
 * read thread handles it as it arrives ({@code docs/DESIGN.md} §11.1, play → configuration): the very
 * next read decodes configuration packets. Queued for the next tick instead, it left the connection
 * in the play state until then, and a configuration keep-alive sent right behind it decoded as the
 * play packet with the same id.
 */
@EnvTest
public class ConfigurationAckReadIntegrationTest {

    @Test
    public void theReadAfterTheAcknowledgementDecodesConfigurationPackets(Env env) throws IOException {
        final ServerProcess process = env.process();
        final List<ClientPacket> handled = new CopyOnWriteArrayList<>();
        process.packetListener().setPlayListener(ClientConfigurationAckPacket.class, (packet, _) -> handled.add(packet));
        process.packetListener().setListener(ConnectionState.CONFIGURATION, ClientKeepAlivePacket.class,
                (packet, _) -> handled.add(packet));
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (SocketChannel peer = SocketChannel.open(server.getLocalAddress());
                 SocketChannel accepted = server.accept()) {
                // The current thread stands in for both socket threads, nothing is parked here
                final PlayerSocketConnection connection = new PlayerSocketConnection(process, accepted,
                        accepted.getRemoteAddress(), Thread.currentThread(), Thread.currentThread());
                var _ = new Player(connection, new GameProfile(UUID.randomUUID(), "reconfig"));
                connection.setClientState(ConnectionState.PLAY);
                // Where the write thread puts the server once StartConfiguration is written
                connection.setServerState(ConnectionState.CONFIGURATION);

                write(peer, ConnectionState.PLAY, new ClientConfigurationAckPacket());
                connection.read(PacketVanilla.CLIENT_PACKET_PARSER);
                assertEquals(ConnectionState.CONFIGURATION, connection.getClientState(),
                        "the acknowledgement must switch the decoding state before the next read");

                write(peer, ConnectionState.CONFIGURATION, new ClientKeepAlivePacket(4_242));
                connection.read(PacketVanilla.CLIENT_PACKET_PARSER);
                assertEquals(List.of(new ClientConfigurationAckPacket(), new ClientKeepAlivePacket(4_242)), handled);
            }
        }
    }

    private static void write(SocketChannel peer, ConnectionState state, ClientPacket packet) throws IOException {
        final NetworkBuffer framed = PacketWriting.allocateTrimmedPacket(state, packet, null, 0);
        while (!framed.writeChannel(peer)) {
            // A blocking channel writes everything, the loop only documents the contract
        }
    }
}
