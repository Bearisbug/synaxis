package dev.gameengine.instance.light;

import dev.gameengine.coordinate.BlockVec;
import dev.gameengine.coordinate.Point;
import dev.gameengine.instance.Chunk;
import dev.gameengine.instance.palette.Palette;
import dev.gameengine.coordinate.Direction;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public interface Light {
    static Light sky() {
        return new SkyLight();
    }

    static Light block() {
        return new BlockLight();
    }

    boolean requiresSend();

    @ApiStatus.Internal
    byte[] array();

    void flip();

    int getLevel(int x, int y, int z);

    void invalidate();

    boolean requiresUpdate();

    void set(byte[] copyArray);

    @ApiStatus.Internal
    Set<Point> calculateInternal(Palette blockPalette,
                                 int chunkX, int chunkY, int chunkZ,
                                 int[] heightmap, int maxY,
                                 LightLookup lightLookup);

    @ApiStatus.Internal
    Set<Point> calculateExternal(Palette blockPalette,
                                 Point[] neighbors,
                                 LightLookup lightLookup,
                                 PaletteLookup paletteLookup);

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

    @FunctionalInterface
    interface LightLookup {
        @Nullable Light light(int x, int y, int z);
    }

    @FunctionalInterface
    interface PaletteLookup {
        @Nullable Palette palette(int x, int y, int z);
    }
}
