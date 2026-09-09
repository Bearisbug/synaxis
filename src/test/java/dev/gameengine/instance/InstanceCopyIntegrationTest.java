package dev.gameengine.instance;

import net.kyori.adventure.key.Key;
import dev.gameengine.instance.block.Block;
import dev.gameengine.instance.block.BlockHandler;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class InstanceCopyIntegrationTest {

    @Test
    public void copiedInstanceIsUsable(Env env) {
        var instance = (InstanceContainer) env.createFlatInstance();
        instance.setChunkSupplier(LightingChunk::new);
        instance.loadChunk(0, 0).join();
        instance.setBlock(0, 41, 0, Block.STONE);

        var copy = assertDoesNotThrow(instance::copy);
        var copiedChunk = copy.getChunk(0, 0);
        assertNotNull(copiedChunk);
        assertTrue(copiedChunk.isLoaded(), "the copied chunk never received onLoad()");

        copy.setBlock(0, 41, 0, Block.DIRT);
        assertEquals(Block.DIRT, copy.getBlock(0, 41, 0));

        // Chunks loaded after the copy must keep the source's supplier and generator
        var generatedChunk = copy.loadChunk(1, 0).join();
        assertInstanceOf(LightingChunk.class, generatedChunk);
        assertEquals(Block.STONE, copy.getBlock(16, 39, 0));
    }

    @Test
    public void copiedInstanceKeepsTickingBlocks(Env env) {
        var ticks = new AtomicInteger();
        BlockHandler handler = new BlockHandler() {
            @Override
            public Key getKey() {
                return Key.key("test:ticking");
            }

            @Override
            public boolean isTickable() {
                return true;
            }

            @Override
            public void tick(Tick tick) {
                ticks.incrementAndGet();
            }
        };
        var instance = (InstanceContainer) env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        instance.setBlock(0, 41, 0, Block.DIRT.withHandler(handler));

        var copiedChunk = instance.copy().getChunk(0, 0);
        assertNotNull(copiedChunk);
        copiedChunk.tick(0);
        assertEquals(1, ticks.get(), "the copied chunk lost its tickable blocks");
    }
}
