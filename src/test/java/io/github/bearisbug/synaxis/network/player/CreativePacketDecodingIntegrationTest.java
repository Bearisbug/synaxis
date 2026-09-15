package io.github.bearisbug.synaxis.network.player;

import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.PacketReading;
import io.github.bearisbug.synaxis.network.packet.PacketVanilla;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientCreativeInventoryActionPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.UUID;
import java.util.zip.DataFormatException;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.RAW_BYTES;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A creative set slot packet carries a full item stack, which is the most expensive thing a client
 * can hand the decoder. Only a creative player may send one, so a player who is not in creative
 * mode has the whole frame dropped before any of it is decoded.
 */
@EnvTest
public class CreativePacketDecodingIntegrationTest {

    /** A creative frame truncated right after the packet id, it cannot decode into a packet. */
    private static NetworkBuffer truncatedCreativeFrame() {
        final int packetId = PacketVanilla.CLIENT_PACKET_PARSER.play()
                .packetInfo(ClientCreativeInventoryActionPacket.class).id();
        final byte[] payload = NetworkBuffer.makeArray(buffer -> {
            buffer.write(VAR_INT, packetId);
            buffer.write(BYTE, (byte) 0); // Half of the slot short, the item never follows
        });
        final NetworkBuffer frame = NetworkBuffer.resizableBuffer();
        frame.write(VAR_INT, payload.length);
        frame.write(RAW_BYTES, payload);
        return frame;
    }

    private static PacketReading.Result<ClientPacket> read(PlayerSocketConnection connection)
            throws DataFormatException {
        return PacketReading.<ClientPacket>readPackets(truncatedCreativeFrame(),
                PacketVanilla.CLIENT_PACKET_PARSER, ConnectionState.PLAY,
                PacketVanilla::nextClientState, false, connection::readClientPacket);
    }

    @Test
    public void nonCreativePlayersNeverReachTheDecoder(Env env) throws IOException, DataFormatException {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (SocketChannel peer = SocketChannel.open(server.getLocalAddress());
                 SocketChannel accepted = server.accept()) {
                assertTrue(peer.isConnected());
                final var connection = new PlayerSocketConnection(env.process(), accepted,
                        accepted.getRemoteAddress(), Thread.currentThread(), Thread.currentThread());
                connection.setClientState(ConnectionState.PLAY);

                // No player yet, the frame is dropped and the connection survives
                assertInstanceOf(PacketReading.Result.Skipped.class, read(connection));
                assertTrue(connection.isOnline());

                final Player player = new Player(connection, new GameProfile(UUID.randomUUID(), "Creative"));
                connection.setPlayer(player);
                assertInstanceOf(PacketReading.Result.Skipped.class, read(connection));
                assertTrue(connection.isOnline());

                // A creative player is decoded as before, so the truncated frame fails to read
                player.setGameMode(GameMode.CREATIVE);
                assertThrows(RuntimeException.class, () -> read(connection));
            }
        }
    }
}
