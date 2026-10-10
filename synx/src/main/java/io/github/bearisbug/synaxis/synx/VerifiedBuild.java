package io.github.bearisbug.synaxis.synx;

import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * A map release the start-up check of {@code API-M3-002} passed, with the region files it checked
 * still mapped: the manifest it was handed and, for every region of the scope it covered, the mapping
 * it hashed. {@link SynxChunkLoader} and the spawn point of the shard read these mappings and never
 * open a region file by its path again, so replacing a file after the check (written next to it and
 * renamed over it) changes nothing the shard reads. The mappings live in {@code Arena.ofShared()}
 * and are never unmapped.
 */
public final class VerifiedBuild {
    private final SynxManifest manifest;
    private final Map<Long, SynxRegion> regions;
    private final long bytesMapped;

    /**
     * @param regions the mapped regions by {@link #key}
     */
    VerifiedBuild(SynxManifest manifest, Map<Long, SynxRegion> regions) {
        this.manifest = manifest;
        this.regions = Map.copyOf(regions);
        long bytes = 0;
        for (SynxRegion region : this.regions.values()) bytes += region.byteSize();
        this.bytesMapped = bytes;
    }

    public SynxManifest manifest() {
        return manifest;
    }

    /** The mapped region file of a region, null when the scope did not reach it. */
    public @Nullable SynxRegion region(int regionX, int regionZ) {
        return regions.get(key(regionX, regionZ));
    }

    /** How many region files are mapped. */
    public int regionCount() {
        return regions.size();
    }

    /** The bytes of every mapped region file. */
    public long bytesMapped() {
        return bytesMapped;
    }

    static long key(int regionX, int regionZ) {
        return (long) regionX << 32 | (regionZ & 0xFFFFFFFFL);
    }
}
