package io.github.bearisbug.synaxis.entity.player;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.ConnectionManager;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The server side cap on chunks sent to one player per tick ({@code docs/DESIGN-PROD.md}
 * {@code REQ-PROD-026}, {@code API-PROD-020}, §19 「改过的客户端不能抬高区块下发速率」): a client
 * reporting 1000 chunks a tick in {@code ChunkBatchReceived} gets at most
 * {@code synaxis.chunk-queue.max-per-tick}, 4 as the shard host sets it and 64 by default.
 */
public class ChunkSendCapTest {

    @Test
    public void aShardHostCapOfFourHoldsAgainstAClientAskingForAThousand() {
        final List<Integer> perTick = chunksPerTick(ServerConfig.builder().maxChunksPerTick(4f), 40);
        assertEquals(4, perTick.stream().mapToInt(Integer::intValue).max().orElseThrow(), perTick.toString());
        // The first batch too: the 9 chunks a client gets before its first report take 3 ticks
        assertEquals(4, perTick.getFirst(), perTick.toString());
    }

    @Test
    public void theEngineDefaultStaysSixtyFour() {
        assertEquals(64f, ServerConfig.defaults().maxChunksPerTick());
        final List<Integer> perTick = chunksPerTick(ServerConfig.builder(), 20);
        assertEquals(64, perTick.stream().mapToInt(Integer::intValue).max().orElseThrow(), perTick.toString());
    }

    /**
     * Joins one player at view distance 8 (289 chunks) and acknowledges every batch with a report of
     * 1000 chunks a tick.
     *
     * @return the chunks sent in each tick, from the join on
     */
    private static List<Integer> chunksPerTick(ServerConfig.Builder config, int ticks) {
        final ServerProcess process = ServerProcess.of(config.insideTest(true).viewablePacket(false).build());
        process.dispatcher().start();
        try {
            final Instance instance = process.instance().createInstanceContainer();
            instance.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
            final ConnectionManager connections = process.connection();
            final CountingConnection connection = new CountingConnection(process);
            final Player player = connections.createPlayer(connection, new GameProfile(UUID.randomUUID(), "Greedy"));
            assertNotNull(player);
            player.setPendingOptions(instance, false);
            player.setRespawnPoint(new Pos(0, 40, 0));
            connections.transitionConfigToPlay(player);
            connection.setClientState(ConnectionState.PLAY);
            connection.setServerState(ConnectionState.PLAY);
            final List<Integer> perTick = new ArrayList<>();
            for (int tick = 0; tick < ticks; tick++) {
                process.ticker().tick(System.nanoTime());
                perTick.add(connection.chunks.getAndSet(0));
                // A modified client asking for far more than the server lets it have
                player.onChunkBatchReceived(1000f);
            }
            return perTick;
        } finally {
            process.stop();
        }
    }

    private static final class CountingConnection extends PlayerConnection {
        final AtomicInteger chunks = new AtomicInteger();

        CountingConnection(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            if (SendablePacket.extractServerPacket(getServerState(), packet) instanceof ChunkDataPacket)
                chunks.incrementAndGet();
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
        }
    }
}
