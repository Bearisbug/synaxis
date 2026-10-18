package io.github.bearisbug.synaxis.coordinate;

/**
 * Helper class to iterate over chunks within a range.
 */
public final class ChunkRange {
    /**
     * The largest range whose square, {@code (2 * range + 1)²} chunks, fits an {@code int}.
     */
    private static final int MAX_RANGE = 23169;

    /**
     * Get the amount of chunks in a square range.
     *
     * @param range the range
     * @return the amount of chunks in the square range
     */
    public static int chunksCount(int range) {
        if (range < 0) throw new IllegalArgumentException("Range cannot be negative: " + range);
        final int square = range * 2 + 1;
        return square * square;
    }

    /**
     * Calls the callback, synchronously on the calling thread, with the chunks of the square of {@code range}
     * around the new center that are outside the square of the same range around the old center: the chunks
     * entering the range when its center moves from the old chunk to the new one.
     * A negative range has no chunk.
     *
     * @param newChunkX the X of the new center chunk
     * @param newChunkZ the Z of the new center chunk
     * @param oldChunkX the X of the old center chunk
     * @param oldChunkZ the Z of the old center chunk
     * @param range     the range of both squares
     * @param callback  called with each entering chunk
     */
    public static void chunksInRangeDiffering(int newChunkX, int newChunkZ,
                                              int oldChunkX, int oldChunkZ,
                                              int range, ChunkConsumer callback) {
        for (int x = newChunkX - range; x <= newChunkX + range; x++) {
            for (int z = newChunkZ - range; z <= newChunkZ + range; z++) {
                if (Math.abs(x - oldChunkX) > range || Math.abs(z - oldChunkZ) > range) {
                    callback.accept(x, z);
                }
            }
        }
    }

    /**
     * Calls back, synchronously on the calling thread, with the chunks that change when the center of the square
     * of {@code range} moves from the old chunk to the new one: {@code newCallback} with the chunks entering the
     * range (in the new square, outside the old one), then {@code oldCallback} with the chunks leaving it (in the
     * old square, outside the new one). A negative range has no chunk.
     *
     * @param newChunkX   the X of the new center chunk
     * @param newChunkZ   the Z of the new center chunk
     * @param oldChunkX   the X of the old center chunk
     * @param oldChunkZ   the Z of the old center chunk
     * @param range       the range of both squares, at most 23169
     * @param newCallback called with each entering chunk
     * @param oldCallback called with each leaving chunk
     * @throws IllegalArgumentException if the range is above 23169
     */
    public static void chunksInRangeDiffering(int newChunkX, int newChunkZ,
                                              int oldChunkX, int oldChunkZ,
                                              int range,
                                              ChunkConsumer newCallback, ChunkConsumer oldCallback) {
        if (range < 0) return;
        if (range > MAX_RANGE) throw new IllegalArgumentException("Range must be at most " + MAX_RANGE + ": " + range);
        // Find the new chunks
        chunksInRangeDiffering(newChunkX, newChunkZ, oldChunkX, oldChunkZ, range, newCallback);
        // Find the old chunks
        chunksInRangeDiffering(oldChunkX, oldChunkZ, newChunkX, newChunkZ, range, oldCallback);
    }

    /**
     * Calls the consumer, synchronously on the calling thread, with every chunk of the square of {@code range}
     * around the center chunk, each exactly once: {@code (2 * range + 1)²} chunks, the center first, then
     * spiralling outwards one square ring at a time. A negative range has no chunk.
     * <p>
     * New implementation comes from <a href="https://github.com/KryptonMC/Krypton/blob/a9eff5463328f34072cdaf37aae3e77b14fcac93/server/src/main/kotlin/org/kryptonmc/krypton/util/math/Maths.kt#L62">Krypton</a>
     * which comes from kotlin port by <a href="https://github.com/Esophose">Esophose</a>, which comes from <a href="https://stackoverflow.com/questions/398299/looping-in-a-spiral">a stackoverflow answer</a>.
     *
     * @param chunkX   the X of the center chunk
     * @param chunkZ   the Z of the center chunk
     * @param range    the range of the square, at most 23169
     * @param consumer called with each chunk
     * @throws IllegalArgumentException if the range is above 23169
     */
    public static void chunksInRange(int chunkX, int chunkZ, int range, ChunkConsumer consumer) {
        if (range < 0) return;
        if (range > MAX_RANGE) throw new IllegalArgumentException("Range must be at most " + MAX_RANGE + ": " + range);
        // Send in a square spiral around the center chunk
        // Note: it's not really required to start at the center anymore since the chunk queue is sorted by distance,
        //       however we still should send a square spiral so this method is still fine, and good for any other case a
        //       spiral might be needed.
        consumer.accept(chunkX, chunkZ);
        final int square = range * 2 + 1;
        final int count = square * square;
        for (int id = 1; id < count; id++) {
            final int index = id - 1;
            // compute radius (inverse arithmetic sum of 8 + 16 + 24 + ...)
            final int radius = (int) Math.floor((Math.sqrt(index + 1.0) - 1) / 2) + 1;
            // compute total point on radius -1 (arithmetic sum of 8 + 16 + 24 + ...)
            final int p = 8 * radius * (radius - 1) / 2;
            // points by face
            final int en = radius * 2;
            // compute de position and shift it so the first is (-r, -r) but (-r + 1, -r)
            // so the square can connect
            final int a = (1 + index - p) % (radius * 8);
            switch (a / (radius * 2)) {
                // find the face (0 = top, 1 = right, 2 = bottom, 3 = left)
                case 0 -> consumer.accept(a - radius + chunkX, -radius + chunkZ);
                case 1 -> consumer.accept(radius + chunkX, a % en - radius + chunkZ);
                case 2 -> consumer.accept(radius - a % en + chunkX, radius + chunkZ);
                case 3 -> consumer.accept(-radius + chunkX, radius - a % en + chunkZ);
                default -> throw new IllegalStateException("unreachable");
            }
        }
    }

    /**
     * Calls the consumer with every chunk of the square of {@code range} around the chunk of the point,
     * as {@link #chunksInRange(int, int, int, ChunkConsumer)}.
     *
     * @param point    a point in the center chunk
     * @param range    the range of the square, at most 23169
     * @param consumer called with each chunk
     * @throws IllegalArgumentException if the range is above 23169
     */
    public static void chunksInRange(Point point, int range, ChunkConsumer consumer) {
        chunksInRange(point.chunkX(), point.chunkZ(), range, consumer);
    }

    @FunctionalInterface
    public interface ChunkConsumer {
        void accept(int chunkX, int chunkZ);
    }
}
