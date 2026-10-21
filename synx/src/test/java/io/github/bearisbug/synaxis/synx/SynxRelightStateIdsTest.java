package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.light.WorldView;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SynxRelight#stateIds}, which the recomputation's snapshot hands the light engine for a section, against
 * {@link WorldView#section}'s default over the snapshot's {@code getBlock}, which asks block by block: the same
 * state ids for every way a block palette stores them. Null and an array of zeros are the same, all air.
 */
public class SynxRelightStateIdsTest {

    @Test
    public void aSingleValueSectionOfStone() {
        final Palette palette = Palette.blocks();
        palette.fill(Block.STONE.stateId());
        assertEquals(0, palette.bitsPerEntry());
        assertSameAsBlockByBlock(palette);
    }

    @Test
    public void aSingleValueSectionOfAir() {
        final Palette palette = Palette.blocks();
        assertEquals(0, palette.bitsPerEntry());
        assertSameAsBlockByBlock(palette);
    }

    @Test
    public void anIndirectSectionWithAir() {
        final Palette palette = Palette.blocks();
        for (int i = 0; i < 4096; i += 3) palette.set(i & 15, i >> 8, (i >> 4) & 15, Block.STONE.stateId());
        palette.set(8, 8, 8, Block.GLOWSTONE.stateId());
        assertEquals(4, palette.bitsPerEntry());
        assertSameAsBlockByBlock(palette);
    }

    @Test
    public void aDirectSection() {
        final Palette palette = Palette.blocks();
        // More distinct states than the widest indirect palette holds, every fifth block air
        palette.setAll((x, y, z) -> (x + z * 16 + y * 256) % 5 == 0 ? 0 : 1 + (x + z * 16 + y * 256) % 1_000);
        assertEquals(15, palette.bitsPerEntry());
        assertSameAsBlockByBlock(palette);
    }

    @Test
    public void aSectionOfCaveAirAndVoidAirOnly() {
        final Palette palette = Palette.blocks();
        palette.set(0, 12, 0, Block.CAVE_AIR.stateId());
        palette.set(1, 12, 0, Block.VOID_AIR.stateId());
        assertSameAsBlockByBlock(palette);
    }

    @Test
    public void anIdPastTheRegistryIsAir() {
        assertSameAsBlockByBlock(loaded(Block.statesCount() + 5));
    }

    @Test
    public void aNegativeIdIsAir() {
        assertSameAsBlockByBlock(loaded(-3));
    }

    /** Air, stone and {@code odd} in thirds, through {@link Palette#load}, which does not check the values. */
    private static Palette loaded(int odd) {
        final long[] indices = new long[256];
        for (int i = 0; i < 4096; i++) indices[i >> 4] |= (long) (i % 3) << ((i & 15) * 4);
        final Palette palette = Palette.blocks();
        palette.load(new int[]{0, Block.STONE.stateId(), odd}, indices);
        assertEquals(4, palette.bitsPerEntry());
        return palette;
    }

    private static void assertSameAsBlockByBlock(Palette palette) {
        // The snapshot's getBlock, SynxRelight.Task#getBlock, over one section at the origin
        final WorldView blockByBlock = new WorldView() {
            @Override
            public int minY() {
                return 0;
            }

            @Override
            public int height() {
                return 16;
            }

            @Override
            public boolean hasChunk(int chunkX, int chunkZ) {
                return chunkX == 0 && chunkZ == 0;
            }

            @Override
            public Block getBlock(int x, int y, int z) {
                return Objects.requireNonNullElse(Block.fromStateId(palette.get(x, y, z)), Block.AIR);
            }
        };
        assertArrayEquals(ids(blockByBlock.section(0, 0, 0)), ids(SynxRelight.stateIds(palette)));
    }

    /** The ids as ints, which an assertion prints as numbers; null as all 0. */
    private static int[] ids(char @Nullable [] states) {
        final int[] ids = new int[4096];
        if (states != null) for (int i = 0; i < ids.length; i++) ids[i] = states[i];
        return ids;
    }
}
