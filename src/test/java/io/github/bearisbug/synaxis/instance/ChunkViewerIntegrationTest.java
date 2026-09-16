package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.coordinate.ChunkRange;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.packet.server.play.BlockChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@EnvTest
public class ChunkViewerIntegrationTest {

    @Test
    public void basicJoin(Env env) {
        Instance instance = env.createFlatInstance();

        var chunk = instance.loadChunk(0, 0).join();
        assertEquals(0, chunk.getViewers().size());

        var player = env.createPlayer(instance, new Pos(0, 40, 0));
        assertEquals(1, chunk.getViewers().size(), "Instance should have 1 viewer");
        assertEquals(player, chunk.getViewers().iterator().next());
    }

    // Regression test: a chunk can get cached between a caller's cache miss and its
    // loading registration, retrieveChunk must then return the cached chunk instead
    // of loading a duplicate that viewers would no longer be registered on
    @Test
    public void retrieveAlreadyCachedChunk(Env env) {
        InstanceContainer instance = (InstanceContainer) env.createFlatInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();
        Chunk retrieved = instance.retrieveChunk(0, 0).join();
        assertSame(chunk, retrieved);
    }

    // Players receive one more ring of chunks than the instance view distance (see
    // Player#effectiveViewDistance), that ring must still get its block updates.
    @Test
    public void outermostChunkStillReceivesBlockChange(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        env.tick();

        final int edgeChunkX = player.effectiveViewDistance();
        assertEquals(env.process().config().chunkViewDistance() + 1, edgeChunkX);
        var chunk = instance.loadChunk(edgeChunkX, 0).join();
        assertEquals(1, chunk.getViewers().size(), "the outermost chunk has no viewer");

        var tracker = connection.trackIncoming(BlockChangePacket.class);
        instance.setBlock(edgeChunkX * 16, 41, 0, Block.STONE);
        tracker.assertSingle(packet -> assertEquals(Block.STONE.stateId(), packet.blockStateId()));
    }

    @Test
    public void renderDistance(Env env) {
        final int viewRadius = env.process().config().chunkViewDistance();
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        // Check initial load
        {
            var tracker = connection.trackIncoming(ChunkDataPacket.class);
            var player = connection.connect(instance, new Pos(0, 40, 0));
            assertEquals(instance, player.getInstance());
            assertEquals(new Pos(0, 40, 0), player.getPosition());
            assertEquals(ChunkRange.chunksCount(player.effectiveViewDistance()), tracker.collect().size());
        }
        // Check chunk#sendChunk
        {
            var tracker = connection.trackIncoming(ChunkDataPacket.class);
            for (int x = -viewRadius; x <= viewRadius; x++) {
                for (int z = -viewRadius; z <= viewRadius; z++) {
                    instance.getChunk(x, z).sendChunk();
                }
            }
            assertEquals(ChunkRange.chunksCount(viewRadius), tracker.collect().size());
        }
    }
}
