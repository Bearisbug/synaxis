package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.world.biome.Biome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chunk changed without its write-lock throws, with or without assertions enabled
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-020}): the chunk packet is serialized under the read-lock,
 * a change racing it can write a section whose bits per entry do not match its data.
 */
@EnvTest
public class ChunkWriteLockIntegrationTest {

    @Test
    public void aBlockSetWithoutTheWriteLockThrows(Env env) {
        final Instance instance = env.createFlatInstance();
        final Chunk chunk = instance.loadChunk(0, 0).join();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> chunk.setBlock(0, 41, 0, Block.STONE));
        assertTrue(thrown.getMessage().contains("write-lock"), thrown.getMessage());
        chunk.lockReadLock();
        try {
            assertThrows(IllegalStateException.class, () -> chunk.setBlock(0, 41, 0, Block.STONE), "under the read-lock");
        } finally {
            chunk.unlockReadLock();
        }
        assertEquals(Block.AIR, instance.getBlock(0, 41, 0), "the block after the refused changes");
    }

    @Test
    public void aBiomeSetWithoutTheWriteLockThrows(Env env) {
        final Instance instance = env.createFlatInstance();
        final Chunk chunk = instance.loadChunk(0, 0).join();
        final var before = instance.getBiome(0, 41, 0);

        assertThrows(IllegalStateException.class, () -> chunk.setBiome(0, 41, 0, Biome.DESERT));
        assertEquals(before, instance.getBiome(0, 41, 0), "the biome after the refused change");
    }

    @Test
    public void theWriteLockHolderChangesTheChunk(Env env) {
        final Instance instance = env.createFlatInstance();
        final Chunk chunk = instance.loadChunk(0, 0).join();

        chunk.lockWriteLock();
        try {
            chunk.setBlock(0, 41, 0, Block.STONE);
            chunk.setBiome(0, 41, 0, Biome.DESERT);
        } finally {
            chunk.unlockWriteLock();
        }
        assertEquals(Block.STONE, instance.getBlock(0, 41, 0));
        assertEquals(Biome.DESERT, instance.getBiome(0, 41, 0));
    }
}
