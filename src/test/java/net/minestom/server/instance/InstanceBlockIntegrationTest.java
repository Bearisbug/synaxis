package net.minestom.server.instance;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.SuspiciousGravelBlockHandler;
import net.minestom.server.instance.block.rule.BlockPlacementRule;
import net.minestom.server.tag.Tag;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnvTest
public class InstanceBlockIntegrationTest {

    // Condition.CACHED means "the cached block object or nothing", the miss is not an unloaded chunk
    @Test
    public void cachedConditionReturnsNullInsteadOfThrowing(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();

        assertNull(instance.getBlock(0, 39, 0, Block.Getter.Condition.CACHED));
        instance.setBlock(0, 39, 0, Block.STONE.withTag(Tag.String("test"), "value"));
        assertEquals(Block.STONE.withTag(Tag.String("test"), "value"),
                instance.getBlock(0, 39, 0, Block.Getter.Condition.CACHED));
        assertThrows(NullPointerException.class,
                () -> instance.getBlock(1000, 39, 1000, Block.Getter.Condition.NONE),
                "An unloaded chunk must still be reported");
    }

    // Light is queried with arbitrary coordinates, out of world ones have no section to look up
    @Test
    public void lightOutsideTheWorldIsZero(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();

        assertEquals(0, instance.getBlockLight(0, -100, 0));
        assertEquals(0, instance.getSkyLight(0, -100, 0));
        assertEquals(0, instance.getBlockLight(0, 400, 0));
        assertEquals(0, instance.getSkyLight(0, 400, 0));
    }

    @Test
    public void basic(Env env) {
        var instance = env.createFlatInstance();
        assertThrows(NullPointerException.class, () -> instance.getBlock(0, 0, 0),
                "No exception throw when getting a block in an unloaded chunk");

        instance.loadChunk(0, 0).join();
        assertEquals(Block.AIR, instance.getBlock(0, 50, 0));

        instance.setBlock(0, 50, 0, Block.GRASS_BLOCK);
        assertEquals(Block.GRASS_BLOCK, instance.getBlock(0, 50, 0));

        instance.setBlock(0, 50, 0, Block.STONE);
        assertEquals(Block.STONE, instance.getBlock(0, 50, 0));

        assertThrows(NullPointerException.class, () -> instance.getBlock(16, 0, 0),
                "No exception throw when getting a block in an unloaded chunk");
        instance.loadChunk(1, 0).join();
        assertEquals(Block.AIR, instance.getBlock(16, 50, 0));
    }

    @Test
    public void unloadCache(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();

        instance.setBlock(0, 50, 0, Block.GRASS_BLOCK);
        assertEquals(Block.GRASS_BLOCK, instance.getBlock(0, 50, 0));

        instance.unloadChunk(0, 0);
        assertThrows(NullPointerException.class, () -> instance.getBlock(0, 0, 0),
                "No exception throw when getting a block in an unloaded chunk");

        instance.loadChunk(0, 0).join();
        assertEquals(Block.AIR, instance.getBlock(0, 50, 0));
    }

    @Test
    public void blockNbt(Env env) {
        var instance = env.createFlatInstance();
        assertThrows(NullPointerException.class, () -> instance.getBlock(0, 0, 0),
                "No exception throw when getting a block in an unloaded chunk");

        instance.loadChunk(0, 0).join();

        var tag = Tag.Integer("key");
        var block = Block.STONE.withTag(tag, 5);
        var point = new Vec(0, 50, 0);
        // Initial placement
        instance.setBlock(point, block);
        assertEquals(5, instance.getBlock(point).getTag(tag));

        // Override
        instance.setBlock(point, block.withTag(tag, 7));
        assertEquals(7, instance.getBlock(point).getTag(tag));

        // Different block type
        instance.setBlock(point, Block.GRASS_BLOCK.withTag(tag, 8));
        assertEquals(8, instance.getBlock(point).getTag(tag));
    }

    @Test
    public void neighborUpdateOrderMatchesVanilla(Env env) {
        List<Point> updated = new ArrayList<>();
        env.process().block().registerBlockPlacementRule(new BlockPlacementRule(Block.CACTUS) {
            @Override
            public @Nullable Block blockPlace(PlacementState placementState) {
                return placementState.block();
            }

            @Override
            public Block blockUpdate(UpdateState updateState) {
                updated.add(updateState.blockPosition());
                return updateState.currentBlock();
            }
        });
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        var center = new Vec(8, 50, 8);
        for (var face : new Vec[]{new Vec(7, 50, 8), new Vec(9, 50, 8), new Vec(8, 49, 8),
                new Vec(8, 51, 8), new Vec(8, 50, 7), new Vec(8, 50, 9)}) {
            instance.setBlock(face, Block.CACTUS);
        }
        updated.clear();

        instance.setBlock(center, Block.STONE);

        // NeighborUpdater.UPDATE_ORDER
        assertEquals(List.of(new Vec(7, 50, 8), new Vec(9, 50, 8), new Vec(8, 49, 8),
                new Vec(8, 51, 8), new Vec(8, 50, 7), new Vec(8, 50, 9)), updated);
    }

    @Test
    public void handlerPresentInPlacementRuleUpdate(Env env) {

        AtomicReference<Block> currentBlock = new AtomicReference<>();
        env.process().block().registerHandler(SuspiciousGravelBlockHandler.INSTANCE.getKey(), () -> SuspiciousGravelBlockHandler.INSTANCE);
        env.process().block().registerBlockPlacementRule(new BlockPlacementRule(Block.SUSPICIOUS_GRAVEL) {
            @Override
            public @Nullable Block blockPlace(PlacementState placementState) {
                return block;
            }

            @Override
            public Block blockUpdate(UpdateState updateState) {
                currentBlock.set(updateState.currentBlock());
                return super.blockUpdate(updateState);
            }
        });

        var instance = env.createFlatInstance();
        var theBlock = Block.SUSPICIOUS_GRAVEL.withHandler(SuspiciousGravelBlockHandler.INSTANCE);
        instance.setBlock(0, 50, 0, theBlock);
        instance.setBlock(1, 50, 0, theBlock);

        assertEquals(theBlock, currentBlock.get());
    }
}
