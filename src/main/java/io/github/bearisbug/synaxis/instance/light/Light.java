package io.github.bearisbug.synaxis.instance.light;

import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.coordinate.Direction;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * The sky or block light a section stores and sends. Arrays are nibble arrays indexed {@code x | z << 4 | y << 8}.
 * {@link #calculateInternal} and {@link #calculateExternal} are the older section-at-a-time engine, which only the
 * lazy {@code LightingChunk#relightSection} runs; a section that runs it is named by a point holding its chunk x,
 * section y and chunk z.
 */
public interface Light {
    /** Sky light of a new section, unset until computed or {@link #set}. */
    static Light sky() {
        return new SkyLight();
    }

    /** Block light of a new section, unset until computed or {@link #set}. */
    static Light block() {
        return new BlockLight();
    }

    /** Whether the light changed since the last call; clears that flag. */
    boolean requiresSend();

    /**
     * The nibble array to send. An all-dark section is {@link LightCompute#UNSET_CONTENT} or
     * {@link LightCompute#EMPTY_CONTENT}. The result may be this light's own array or a shared constant: never
     * write it.
     */
    @ApiStatus.Internal
    byte[] array();

    /** Publishes the external light the last {@link #calculateExternal} built. */
    void flip();

    /** Level at section-local {@code (x, y, z)}, each 0..15. */
    int getLevel(int x, int y, int z);

    /** Drops the light that entered from other sections and marks the light to be recomputed and sent. */
    void invalidate();

    /** Whether the light was invalidated and has not been computed or set since. */
    boolean requiresUpdate();

    /** Replaces the light with a copy of {@code copyArray}, computed elsewhere, and marks it valid and to be sent. */
    void set(byte[] copyArray);

    /**
     * Computes the light of this section's own sources and drops the external light of the 3x3x3 sections centred
     * on it; returns them, which must recompute their external light.
     */
    @ApiStatus.Internal
    Set<Point> calculateInternal(Palette blockPalette,
                                 int chunkX, int chunkY, int chunkZ,
                                 int[] heightmap, int maxY,
                                 LightLookup lightLookup);

    /**
     * Computes the light entering from {@code neighbors}, which {@link #flip} then publishes; returns the neighbours
     * whose border with this section got brighter.
     */
    @ApiStatus.Internal
    Set<Point> calculateExternal(Palette blockPalette,
                                 Point[] neighbors,
                                 LightLookup lightLookup,
                                 PaletteLookup paletteLookup);

    /** The six sections next to a section, in {@link Direction} order; null where no chunk is loaded or the world ends. */
    @ApiStatus.Internal
    static Point[] getNeighbors(Chunk chunk, int sectionY) {
        final int chunkX = chunk.getChunkX(), chunkZ = chunk.getChunkZ();

        Point[] links = new BlockVec[LightCompute.DIRECTIONS.length];
        for (Direction direction : LightCompute.DIRECTIONS) {
            final int x = chunkX + direction.normalX();
            final int z = chunkZ + direction.normalZ();
            final int y = sectionY + direction.normalY();

            Chunk foundChunk = chunk.getInstance().getChunk(x, z);
            if (foundChunk == null) continue;
            if (y >= foundChunk.getMaxSection() || y < foundChunk.getMinSection())
                continue;

            links[direction.ordinal()] = new BlockVec(foundChunk.getChunkX(), y, foundChunk.getChunkZ());
        }
        return links;
    }

    /** The light of a section, or null where there is none. */
    @FunctionalInterface
    interface LightLookup {
        @Nullable Light light(int chunkX, int sectionY, int chunkZ);
    }

    /** The block palette of a section, or null where there is none. */
    @FunctionalInterface
    interface PaletteLookup {
        @Nullable Palette palette(int chunkX, int sectionY, int chunkZ);
    }
}
