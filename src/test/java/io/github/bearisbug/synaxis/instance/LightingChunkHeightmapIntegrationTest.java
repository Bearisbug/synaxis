package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The heightmaps of a {@link LightingChunk} after blocks changed without {@code setBlock}, as a
 * generation fork or a plugin writing a section does it: {@link Chunk#invalidate()}, which
 * {@link Instance#invalidateSection} calls, makes the next read compute them again.
 */
@EnvTest
class LightingChunkHeightmapIntegrationTest {

    @Test
    void anInvalidatedLightingChunkComputesItsHeightmapsAgain(Env env) {
        final InstanceContainer instance = (InstanceContainer) env.createFlatInstance();
        instance.setChunkSupplier(LightingChunk::new);
        instance.loadChunk(0, 0).join();
        final Chunk chunk = assertInstanceOf(LightingChunk.class, instance.getChunk(0, 0));
        assertEquals(39, chunk.motionBlockingHeightmap().getHeight(0, 0), "the flat ground's top");
        assertEquals(39, chunk.worldSurfaceHeightmap().getHeight(0, 0));

        chunk.lockWriteLock();
        try {
            chunk.getSectionAt(45).blockPalette().set(0, 45 & 15, 0, Block.STONE.stateId());
        } finally {
            chunk.unlockWriteLock();
        }
        instance.invalidateSection(0, 45 >> 4, 0);

        assertEquals(45, chunk.motionBlockingHeightmap().getHeight(0, 0), "the stone written into the section");
        assertEquals(45, chunk.worldSurfaceHeightmap().getHeight(0, 0));
    }
}
