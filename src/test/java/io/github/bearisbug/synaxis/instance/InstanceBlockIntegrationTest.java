package io.github.bearisbug.synaxis.instance;

import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.event.instance.InstanceBlockUpdateEvent;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.block.SuspiciousGravelBlockHandler;
import io.github.bearisbug.synaxis.instance.block.rule.BlockPlacementRule;
import io.github.bearisbug.synaxis.tag.Tag;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        final var handler = new SuspiciousGravelBlockHandler(env.process(), true);
        env.process().block().registerHandler(handler.getKey(), () -> handler);
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
        var theBlock = Block.SUSPICIOUS_GRAVEL.withHandler(handler);
        instance.setBlock(0, 50, 0, theBlock);
        instance.setBlock(1, 50, 0, theBlock);

        assertEquals(theBlock, currentBlock.get());
    }

    // A rule returning null cancels the placement before the chunk is asked to change anything
    @Test
    public void aPlacementRuleReturningNullCancelsThePlacement(Env env) {
        env.process().block().registerBlockPlacementRule(new BlockPlacementRule(Block.STONE) {
            @Override
            public @Nullable Block blockPlace(PlacementState placementState) {
                return null;
            }
        });
        final AtomicInteger chunkWrites = new AtomicInteger();
        final InstanceContainer instance = env.process().instance().createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
        instance.setChunkSupplier((owner, chunkX, chunkZ) -> new DynamicChunk(owner, chunkX, chunkZ) {
            @Override
            public void setBlock(int x, int y, int z, Block block, @Nullable BlockHandler.Placement placement,
                                 @Nullable BlockHandler.Destroy destroy) {
                chunkWrites.incrementAndGet();
                super.setBlock(x, y, z, block, placement, destroy);
            }
        });
        instance.loadChunk(0, 0).join();
        final AtomicInteger destroyed = new AtomicInteger();
        final Block grass = Block.SHORT_GRASS.withHandler(new BlockHandler() {
            @Override
            public void onDestroy(Destroy destroy) {
                destroyed.incrementAndGet();
            }

            @Override
            public Key getKey() {
                return Key.key("synaxis:test");
            }
        });
        final Vec position = new Vec(0, 40, 0);
        instance.setBlock(position, grass);
        chunkWrites.set(0);
        final List<InstanceBlockUpdateEvent> updates = new ArrayList<>();
        instance.eventNode().addListener(InstanceBlockUpdateEvent.class, updates::add);

        assertFalse(instance.placeBlock(new BlockHandler.Placement(Block.STONE, grass, instance, position)),
                "the rule cancelled the placement");
        assertEquals(grass, instance.getBlock(position), "the world is left as it is");
        assertEquals(0, chunkWrites.get(), "the chunk is never asked to change");
        assertEquals(0, destroyed.get(), "the block that is there is not destroyed");
        assertEquals(List.of(), updates);

        // The cancelled placement is no change of this tick, the same block can still be set
        instance.setBlock(position, Block.STONE);
        assertEquals(Block.STONE, instance.getBlock(position));
    }

    // The handler of the placed block is told about the block the rule chose, not the one asked for
    @Test
    public void theHandlerGetsTheBlockThePlacementRuleChose(Env env) {
        env.process().block().registerBlockPlacementRule(new BlockPlacementRule(Block.FURNACE) {
            @Override
            public @Nullable Block blockPlace(PlacementState placementState) {
                return placementState.block().withProperty("facing", "east");
            }
        });
        final AtomicReference<Block> placed = new AtomicReference<>();
        final Block furnace = Block.FURNACE.withHandler(new BlockHandler() {
            @Override
            public void onPlace(Placement placement) {
                placed.set(placement.getBlock());
            }

            @Override
            public Key getKey() {
                return Key.key("synaxis:test");
            }
        });
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        final Vec position = new Vec(0, 40, 0);

        assertTrue(instance.placeBlock(new BlockHandler.Placement(furnace, Block.AIR, instance, position)));
        assertEquals("east", instance.getBlock(position).getProperty("facing"));
        assertEquals(instance.getBlock(position), placed.get(), "the block the handler is placed with");
    }
}
