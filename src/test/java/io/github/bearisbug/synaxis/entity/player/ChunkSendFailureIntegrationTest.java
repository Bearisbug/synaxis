package io.github.bearisbug.synaxis.entity.player;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.instance.DynamicChunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChunkBatchReceivedPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTeleportConfirmPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.player.ClientSettings;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chunk whose packet cannot be taken when its turn comes in a chunk batch is not lost ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-020}): it was dequeued already, and dropped the client would never get it. It goes back in the send
 * queue, the batch goes on with the chunks behind it, and the next batch sends it, once.
 * <p>
 * The player is an engine player, whose chunks wait in the send queue, at view distance 2: a ring of radius 3.
 */
@EnvTest
public class ChunkSendFailureIntegrationTest {

    private static final int VIEW_DISTANCE = 2;
    private static final int RADIUS = VIEW_DISTANCE + 1;
    private static final double Y = 40;

    @Test
    public void aChunkWhosePacketThrowsIsSentInALaterBatchOnce(Env env) {
        final List<Throwable> thrown = new CopyOnWriteArrayList<>();
        env.process().exception().setExceptionHandler(thrown::add);
        final InstanceContainer instance = env.process().instance().createInstanceContainer();
        instance.setChunkSupplier(FailingChunk::new);
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
        final List<CompletableFuture<?>> loads = new ArrayList<>();
        for (int x = -RADIUS; x <= RADIUS + 1; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) loads.add(instance.loadChunk(x, z));
        }
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).join();
        final TestConnection connection = env.createConnection();
        final Player player = join(env, connection, instance);
        acknowledge(env, player, 20);
        assertTrue(thrown.isEmpty(), "exceptions before the move " + thrown);

        // Moving one chunk east queues the column at x 4, its chunk at z 0, the closest to the new center, first.
        // Taking the packet of that chunk throws, the first time only
        final int column = 1 + RADIUS;
        final FailingChunk failing = (FailingChunk) Objects.requireNonNull(instance.getChunk(column, 0));
        failing.failNext.set(true);
        final Collector<ChunkDataPacket> inTheMove = connection.trackIncoming(ChunkDataPacket.class);
        player.addPacketToQueue(new ClientPlayerPositionPacket(new Vec(8.5 + 16, Y, 8.5), true, false));
        env.tick();
        final List<String> sentInTheMove = names(inTheMove.collect());
        final Collector<ChunkDataPacket> afterTheMove = connection.trackIncoming(ChunkDataPacket.class);
        acknowledge(env, player, 10);

        final Map<String, Integer> times = new TreeMap<>();
        for (String chunk : sentInTheMove) times.merge(chunk, 1, Integer::sum);
        for (String chunk : names(afterTheMove.collect())) times.merge(chunk, 1, Integer::sum);
        System.out.println("Chunk " + column + ",0 threw taking its packet: sent in the tick of the move " + sentInTheMove
                + ", chunks sent " + times + ", exceptions reported " + thrown.size());
        assertEquals(1, thrown.size(), "exceptions reported " + thrown);
        assertTrue(thrown.getFirst() instanceof IllegalStateException && FailingChunk.MESSAGE.equals(thrown.getFirst().getMessage()),
                "the exception reported " + thrown.getFirst());
        assertEquals(1, times.getOrDefault(column + ",0", 0), "times chunk " + column + ",0 was sent, chunks sent " + times);
        assertEquals(2 * RADIUS + 1, times.size(), "chunks of column " + column + " sent " + times);
        assertTrue(times.values().stream().allMatch(count -> count == 1), "each chunk sent once: " + times);
        assertEquals(2 * RADIUS, sentInTheMove.size(), "the batch of the move goes on behind the chunk that threw: " + sentInTheMove);
    }

    /** A flat chunk whose next packet taken throws once {@link #failNext} is set. */
    private static final class FailingChunk extends DynamicChunk {
        static final String MESSAGE = "the packet of the chunk cannot be taken";
        final AtomicBoolean failNext = new AtomicBoolean();

        FailingChunk(Instance instance, int chunkX, int chunkZ) {
            super(instance, chunkX, chunkZ);
        }

        @Override
        public SendablePacket getFullDataPacket() {
            if (failNext.compareAndSet(true, false)) throw new IllegalStateException(MESSAGE);
            return super.getFullDataPacket();
        }
    }

    /** Joins an engine player at view distance 2 in chunk (0, 0), its last teleport confirmed. */
    private static Player join(Env env, TestConnection connection, Instance instance) {
        env.process().connection().setPlayerProvider(Player::new);
        env.process().eventHandler().addListener(AsyncPlayerConfigurationEvent.class,
                event -> event.getPlayer().refreshSettings(ClientSettings.defaultSettings(VIEW_DISTANCE)));
        final Player player = connection.connect(instance, new Pos(8.5, Y, 8.5));
        assertEquals(RADIUS, player.effectiveViewDistance());
        player.addPacketToQueue(new ClientTeleportConfirmPacket(player.getLastSentTeleportId()));
        return player;
    }

    /** Acknowledges a batch every tick, as a client does, for {@code ticks} ticks. */
    private static void acknowledge(Env env, Player player, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            player.addPacketToQueue(new ClientChunkBatchReceivedPacket(64f));
            env.tick();
        }
    }

    private static List<String> names(List<ChunkDataPacket> chunks) {
        return chunks.stream().filter(chunk -> chunk.chunkX() == 1 + RADIUS).map(chunk -> chunk.chunkX() + "," + chunk.chunkZ()).toList();
    }
}
