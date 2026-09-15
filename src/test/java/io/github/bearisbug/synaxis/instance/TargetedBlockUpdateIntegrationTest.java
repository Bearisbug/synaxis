package io.github.bearisbug.synaxis.instance;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockEntityType;
import io.github.bearisbug.synaxis.network.packet.server.play.BlockEntityDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.MultiBlockChangePacket;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@EnvTest
public class TargetedBlockUpdateIntegrationTest {

    // A generator fork applied to an already-loaded, viewed chunk must push a MultiBlockChangePacket
    // to that chunk's viewers instead of resending the whole chunk.
    @Test
    public void forkSendsMultiBlockChange(Env env) {
        var instance = env.process().instance().createInstanceContainer();

        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(8, 42, 8)); // spawn in chunk (0, 0)

        // Target chunk (-1, 0) is adjacent to spawn, so it is guaranteed to be loaded and viewed.
        // The origin chunk sits well beyond the loaded radius so it is not auto-loaded at connect time;
        // generating it later fires a fresh fork. The fork spans from the origin across to the target.
        final int targetChunkX = -1;
        final int originChunkX = -(player.effectiveViewDistance() + 3);
        final int spanChunks = targetChunkX - originChunkX; // > 0
        instance.setGenerator(unit -> {
            final Point start = unit.absoluteStart();
            if (start.chunkX() != originChunkX || start.chunkZ() != 0) return;
            var fork = unit.fork(start, unit.absoluteEnd().add(spanChunks * 16, 0, 0));
            fork.modifier().setRelative(spanChunks * 16, 0, 0, Block.STONE); // lands in the target chunk
        });

        assertNull(instance.getChunk(originChunkX, 0), "origin chunk must start unloaded");
        assertNotNull(instance.getChunk(targetChunkX, 0), "target chunk must be loaded and viewed");

        Collector<MultiBlockChangePacket> multiBlock = connection.trackIncoming(MultiBlockChangePacket.class);
        Collector<ChunkDataPacket> chunkData = connection.trackIncoming(ChunkDataPacket.class);

        // Generating the origin chunk forks into the already-loaded, viewed target chunk.
        instance.loadChunk(originChunkX, 0).join();
        assertEquals(Block.STONE, instance.getBlock(targetChunkX * 16, -64, 0), "fork must have applied to the target chunk");

        multiBlock.assertCount(1);
        chunkData.assertCount(0);
    }

    // A generator fork applied to an already loaded, viewed chunk containing a block entity
    // must push a BlockEntityDataPacket to viewers with the correct block entity type and NBT data.
    @Test
    public void forkSendsBlockEntityData(Env env) {
        var instance = env.process().instance().createInstanceContainer();

        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(8, 42, 8)); // spawn in chunk (0, 0)

        final int targetChunkX = -1;
        final int originChunkX = -(player.effectiveViewDistance() + 3);
        final int spanChunks = targetChunkX - originChunkX;

        var nbt = CompoundBinaryTag.builder().putString("custom_key", "custom_value").build();
        var chestBlock = Block.CHEST.withNbt(nbt);

        instance.setGenerator(unit -> {
            final Point start = unit.absoluteStart();
            if (start.chunkX() != originChunkX || start.chunkZ() != 0) return;
            var fork = unit.fork(start, unit.absoluteEnd().add(spanChunks * 16, 0, 0));
            fork.modifier().setRelative(spanChunks * 16, 0, 0, chestBlock);
        });

        assertNull(instance.getChunk(originChunkX, 0), "origin chunk must start unloaded");
        assertNotNull(instance.getChunk(targetChunkX, 0), "target chunk must be loaded and viewed");

        Collector<BlockEntityDataPacket> blockEntityCollector = connection.trackIncoming(BlockEntityDataPacket.class);

        // Generating the origin chunk forks into the already loaded, viewed target chunk.
        instance.loadChunk(originChunkX, 0).join();

        blockEntityCollector.assertCount(1);
        var packet = blockEntityCollector.collect().getFirst();
        assertEquals(new BlockVec(targetChunkX * 16, -64, 0), packet.blockPosition());
        assertEquals(BlockEntityType.CHEST, packet.type());
        assertNotNull(packet.data());
        assertEquals("custom_value", packet.data().getString("custom_key"));
    }
}
