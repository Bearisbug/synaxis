package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerPacketOutEvent;
import io.github.bearisbug.synaxis.network.packet.PacketReading;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.FinishConfigurationPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerSocketConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A {@link PlayerPacketOutEvent} listener that cancels every packet it sees, the end of the
 * configuration included.
 */
@EnvTest
public class StateChangingPacketCancelIntegrationTest {

    /**
     * Both ends switch their connection state at {@link FinishConfigurationPacket}: dropped, the
     * server would stay in the configuration state while the client waits for its end, and neither
     * sends a keep-alive any more. The cancel is ignored for it, and still drops any other packet.
     */
    @Test
    public void cancellingTheEndOfTheConfigurationIsIgnored(Env env) throws Exception {
        env.process().eventHandler().addListener(PlayerPacketOutEvent.class, event -> event.setCancelled(true));

        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (SocketChannel peer = SocketChannel.open(server.getLocalAddress());
                 SocketChannel accepted = server.accept()) {
                final var connection = new PlayerSocketConnection(env.process(), accepted, accepted.getRemoteAddress(),
                        Thread.currentThread(), Thread.currentThread());
                connection.setClientState(ConnectionState.CONFIGURATION);
                connection.setPlayer(new Player(connection, new GameProfile(UUID.randomUUID(), "Cancelled")));

                connection.sendPacket(new PluginMessagePacket("synaxis:noise", new byte[8]));
                connection.sendPacket(new FinishConfigurationPacket());
                connection.flushSync();

                assertEquals(ConnectionState.PLAY, connection.getServerState(), "the state moves with the packet");
                // The frame header is three bytes, the packet one byte of id
                peer.socket().setSoTimeout(5_000);
                final InputStream in = peer.socket().getInputStream();
                final byte[] frame = in.readNBytes(4);
                assertEquals(0, in.available(), "the cancelled plugin message is not written");
                final PacketReading.Result<ServerPacket> read = PacketReading.readServers(
                        NetworkBuffer.wrap(frame, 0, frame.length, env.process().registries()),
                        ConnectionState.CONFIGURATION, false);
                assertInstanceOf(PacketReading.Result.Success.class, read);
                final var success = (PacketReading.Result.Success<ServerPacket>) read;
                assertEquals(1, success.packets().size());
                assertInstanceOf(FinishConfigurationPacket.class, success.packets().getFirst().packet());
            }
        }
    }
}
