package dev.gameengine.instance;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import dev.gameengine.adventure.EngineAdventure;
import dev.gameengine.coordinate.Pos;
import dev.gameengine.coordinate.Vec;
import dev.gameengine.instance.block.Block;
import dev.gameengine.instance.block.BlockHandler;
import dev.gameengine.network.packet.server.play.BlockChangePacket;
import dev.gameengine.network.packet.server.play.BlockEntityDataPacket;
import dev.gameengine.tag.Tag;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.gameengine.testing.TestUtils.assertPoint;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
