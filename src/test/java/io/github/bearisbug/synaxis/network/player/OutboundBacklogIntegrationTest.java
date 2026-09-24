package io.github.bearisbug.synaxis.network.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerPacketOutEvent;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.server.BufferedPacket;
import io.github.bearisbug.synaxis.network.packet.server.FramedPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import net.kyori.adventure.text.TranslatableComponent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The outbound backlog of one connection ({@code docs/DESIGN-PROD.md} {@code API-PROD-020}, §19
 * 「出站积压超限先丢位置包再断线」) with the engine default of 4 MiB. Nothing writes to the socket
 * while the packets are offered, as with a peer that stopped reading; the test flushes afterwards
 * with a peer that reads again.
 */
@EnvTest
public class OutboundBacklogIntegrationTest {

    private static final int PACKET_BYTES = 16_384;

    @Test
    public void movementOverTheBacklogIsShedWithoutAKick(Env env) throws Exception {
        try (Harness harness = new Harness(env)) {
            // 400 × 16 KiB = 6.25 MiB of movement: the 257th goes over 4 MiB and sheds all 257, the
            // 143 after it are not queued while the shed ones are still in the queue
            for (int i = 0; i < 400; i++) harness.connection.sendPacket(harness.movement());
            assertTrue(harness.connection.isOnline());
            assertEquals(0, harness.connection.backlogBytes(), "the shed movement no longer counts");
            assertEquals(0, harness.kicks());

            // The write thread skips what was shed: the peer gets none of it
            harness.flushUntil(() -> harness.flushes() >= 3);
            assertEquals(0, harness.received.get(), "shed movement must not reach the peer");

            // Once the shed movement is off the queue, movement is queued and written again
            for (int i = 0; i < 10; i++) harness.connection.sendPacket(harness.movement());
            harness.flushUntil(() -> harness.received.get() >= 10L * PACKET_BYTES);
            assertEquals(10L * PACKET_BYTES, harness.received.get());
            assertTrue(harness.connection.isOnline());
        }
    }

    @Test
    public void movementIsShedBeforeTheConnectionIsKicked(Env env) throws Exception {
        try (Harness harness = new Harness(env)) {
            // 100 movement + 200 other = 4.69 MiB: shedding the movement leaves 3.13 MiB, no kick
            for (int i = 0; i < 100; i++) harness.connection.sendPacket(harness.movement());
            for (int i = 0; i < 200; i++) harness.connection.sendPacket(harness.framed());
            assertTrue(harness.connection.isOnline(), "shedding the movement brings the backlog under the limit");
            assertEquals(200L * PACKET_BYTES, harness.connection.backlogBytes());
            assertEquals(0, harness.kicks());

            // 57 more is 257 × 16 KiB of what cannot be shed, over 4 MiB
            for (int i = 0; i < 57; i++) harness.connection.sendPacket(harness.framed());
            assertFalse(harness.connection.isOnline());
            assertEquals(1, harness.kicks());
            assertEquals("synaxis:outbound-backlog", harness.disconnectKey());
        }
    }

    /** A connection in the play state with a player whose write thread is the test thread. */
    private static final class Harness implements AutoCloseable {
        private final Env env;
        private final ServerSocketChannel server;
        private final SocketChannel peer;
        private final SocketChannel accepted;
        final PlayerSocketConnection connection;
        final List<DisconnectPacket> peerDisconnects = new CopyOnWriteArrayList<>();
        final AtomicLong received = new AtomicLong();
        private final Thread reader;
        private int flushes;
        private final NetworkBuffer framedBody;
        private final NetworkBuffer movementBody;

        Harness(Env env) throws IOException {
            this.env = env;
            this.server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            this.peer = SocketChannel.open(server.getLocalAddress());
            this.accepted = server.accept();
            this.connection = new PlayerSocketConnection(env.process(), accepted, accepted.getRemoteAddress(),
                    Thread.currentThread(), Thread.currentThread());
            connection.setClientState(ConnectionState.PLAY);
            connection.setServerState(ConnectionState.PLAY);
            connection.setPlayer(new Player(connection, new GameProfile(UUID.randomUUID(), "Slow")));
            env.process().eventHandler().addListener(PlayerPacketOutEvent.class, event -> {
                if (event.getPacket() instanceof DisconnectPacket disconnect) peerDisconnects.add(disconnect);
            });
            // A plugin message framed to exactly 16 KiB, the frame header included
            final long overhead = frame(0).capacity();
            this.framedBody = frame((int) (PACKET_BYTES - overhead));
            assertEquals(PACKET_BYTES, framedBody.capacity(), "a framed packet of 16 KiB");
            this.movementBody = NetworkBuffer.resizableBuffer();
            movementBody.write(NetworkBuffer.RAW_BYTES, new byte[PACKET_BYTES]);
            // Nothing is written before the test flushes, as if the peer had stopped reading until then
            this.reader = Thread.ofVirtual().start(() -> {
                final ByteBuffer buffer = ByteBuffer.allocate(65_536);
                try {
                    int read;
                    while ((read = peer.read(buffer.clear())) >= 0) received.addAndGet(read);
                } catch (IOException _) {
                    // Closed by the test
                }
            });
        }

        private static NetworkBuffer frame(int payload) {
            return PacketWriting.allocateTrimmedPacket(ConnectionState.PLAY,
                    new PluginMessagePacket("synaxis:noise", new byte[payload]), null, 0);
        }

        BufferedPacket movement() {
            return new BufferedPacket(movementBody, 0, PACKET_BYTES);
        }

        FramedPacket framed() {
            return new FramedPacket(new PluginMessagePacket("synaxis:noise", new byte[0]), framedBody);
        }

        long kicks() {
            return env.process().connection().traffic().backlogKicks();
        }

        int flushes() {
            return flushes;
        }

        void flushUntil(BooleanSupplier done) throws IOException, InterruptedException {
            for (int i = 0; i < 2_000 && !done.getAsBoolean(); i++) {
                connection.flushSync();
                flushes++;
                Thread.sleep(1);
            }
        }

        String disconnectKey() throws IOException, InterruptedException {
            flushUntil(() -> !peerDisconnects.isEmpty());
            assertEquals(1, peerDisconnects.size(), "one disconnect packet");
            return assertInstanceOf(TranslatableComponent.class, peerDisconnects.getFirst().message()).key();
        }

        @Override
        public void close() throws IOException {
            peer.close();
            accepted.close();
            server.close();
            try {
                reader.join(5_000);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
