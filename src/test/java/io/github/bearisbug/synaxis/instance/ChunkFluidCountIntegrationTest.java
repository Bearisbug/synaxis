package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.ChunkData;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class ChunkFluidCountIntegrationTest {
    @Test
    public void sectionFluidCount(Env env) {
        var instance = env.createFlatInstance();
        var chunk = instance.loadChunk(0, 0).join();

        final int water = 5;
        for (int y = 40; y < 40 + water; y++) instance.setBlock(0, y, 0, Block.WATER);
        assertEquals(water, sectionFluidCount(env, chunk, 40));

        // a removed fluid block leaves the rest counted (the client decrements from this value)
        instance.setBlock(0, 40, 0, Block.STONE);
        assertEquals(water - 1, sectionFluidCount(env, chunk, 40));
    }

    @Test
    public void waterloggedBlockCountsAsFluid(Env env) {
        var instance = env.createFlatInstance();
        var chunk = instance.loadChunk(0, 0).join();

        instance.setBlock(0, 40, 0, Block.OAK_FENCE.withProperty("waterlogged", "true"));
        assertEquals(1, sectionFluidCount(env, chunk, 40));
    }

    @Test
    public void dryWaterloggableBlockDoesNotCountAsFluid(Env env) {
        var instance = env.createFlatInstance();
        var chunk = instance.loadChunk(0, 0).join();

        instance.setBlock(0, 40, 0, Block.OAK_FENCE);
        assertEquals(0, sectionFluidCount(env, chunk, 40));
    }

    private static int sectionFluidCount(Env env, Chunk chunk, int blockY) {
        var packet = (ChunkDataPacket) SendablePacket.extractServerPacket(ConnectionState.PLAY, chunk.getFullDataPacket());
        Assertions.assertNotNull(packet);
        final byte[] data = packet.chunkData().data();
        final var sectionType = ChunkData.Section.networkType(env.process().biome().size());
        final NetworkBuffer buffer = NetworkBuffer.wrap(data, 0, data.length);
        ChunkData.Section section = null;
        for (int i = (blockY >> 4) - chunk.getMinSection(); i >= 0; i--) section = buffer.read(sectionType);
        Assertions.assertNotNull(section);
        return section.liquidCount();
    }
}
