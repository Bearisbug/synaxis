package io.github.bearisbug.synaxis.instance.light;

import io.github.bearisbug.synaxis.instance.LightingChunk;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class HighSectionLightIntegrationTest {

    // The overworld starts at section -4, so its topmost sections are the ones where an
    // off-by-minSection neighbour check silently drops every cross-section propagation.
    @Test
    public void blockLightPropagatesAcrossTopSections(Env env) {
        var instance = env.createEmptyInstance();
        instance.setChunkSupplier(LightingChunk::new);
        instance.loadChunk(0, 0).join();

        instance.setBlock(1, 288, 1, Block.GLOWSTONE); // section 18
        assertEquals(15, instance.getBlockLight(1, 288, 1));
        assertEquals(14, instance.getBlockLight(1, 287, 1), "light did not cross the section 18 -> 17 boundary");
    }
}
