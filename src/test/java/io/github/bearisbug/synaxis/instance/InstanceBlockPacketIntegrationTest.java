package io.github.bearisbug.synaxis.instance;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.adventure.EngineAdventure;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.network.packet.server.play.BlockChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.BlockEntityDataPacket;
import io.github.bearisbug.synaxis.tag.Tag;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.bearisbug.synaxis.testing.TestUtils.assertPoint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnvTest
public class InstanceBlockPacketIntegrationTest {

    @Test
    public void replaceAir(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        connection.connect(instance, new Pos(0, 40, 0));

        var blockPoint = new Vec(5, 41, 0);

        assertEquals(Block.AIR, instance.getBlock(blockPoint));

        var tracker = connection.trackIncoming();
        instance.setBlock(blockPoint, Block.STONE);
        tracker.assertSingle(BlockChangePacket.class, packet -> {
            assertPoint(blockPoint, packet.blockPosition());
            assertEquals(Block.STONE.stateId(), packet.blockStateId());
        });

        assertEquals(Block.STONE, instance.getBlock(blockPoint));
    }

    @Test
    public void placeBlockEntity(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        connection.connect(instance, new Pos(0, 40, 0));

        var blockPoint = new Vec(5, 41, 0);

        BlockHandler signHandler = new BlockHandler() {
            @Override
            public List<Tag<?>> getBlockEntityTags() {
                return List.of(Tag.Byte("is_waxed"));
            }

            @Override
            public Key getKey() {
                return Key.key("sign");
            }
        };

        assertEquals(Block.AIR, instance.getBlock(blockPoint));

        final Block block;
        final CompoundBinaryTag data;
        try {
            data = EngineAdventure.tagStringIO().asCompound("{\"is_waxed\":1B}");
            block = Block.OAK_SIGN.withHandler(signHandler).withNbt(data);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }

        var blockChangeTracker = connection.trackIncoming(BlockChangePacket.class);
        var blockEntityTracker = connection.trackIncoming(BlockEntityDataPacket.class);
        instance.setBlock(blockPoint, block);
        blockChangeTracker.assertSingle(packet -> {
            assertPoint(blockPoint, packet.blockPosition());
            assertEquals(block.stateId(), packet.blockStateId());
        });
        blockEntityTracker.assertSingle(packet -> {
            assertPoint(blockPoint, packet.blockPosition());
            assertEquals(block.blockEntityType(), packet.type());
            assertEquals(data, packet.data());
        });

        assertEquals(block, instance.getBlock(blockPoint));
    }

    // A handler throwing in onPlace once the chunk has the block: the viewers are told the block the
    // chunk holds, and its heightmap has it, before the exception goes on to the caller
    @Test
    public void aThrowingPlacementStillTellsTheViewers(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        connection.connect(instance, new Pos(0, 40, 0));
        var blockPoint = new Vec(5, 45, 0);
        final BlockHandler throwing = new BlockHandler() {
            @Override
            public void onPlace(Placement placement) {
                throw new IllegalStateException("a failing placement");
            }

            @Override
            public Key getKey() {
                return Key.key("test:throwing");
            }
        };

        var tracker = connection.trackIncoming(BlockChangePacket.class);
        assertThrows(IllegalStateException.class, () -> instance.setBlock(blockPoint, Block.STONE.withHandler(throwing)));

        tracker.assertSingle(packet -> {
            assertPoint(blockPoint, packet.blockPosition());
            assertEquals(Block.STONE.stateId(), packet.blockStateId());
        });
        assertEquals(Block.STONE.stateId(), instance.getBlock(blockPoint).stateId());
        assertEquals(45, instance.getChunkAt(blockPoint).motionBlockingHeightmap().getHeight(5, 0),
                "the heightmap of the column");
    }
}
