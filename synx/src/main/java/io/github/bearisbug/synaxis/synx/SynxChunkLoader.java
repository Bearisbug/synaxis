package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.ChunkLoader;
import io.github.bearisbug.synaxis.instance.Instance;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Loads the chunks of a map release ({@code docs/DESIGN-M3.md} {@code API-M3-005}).
 * <p>
 * The M3-C step 0 contract: the signatures the shard host builds against. The chunk track
 * replaces the bodies.
 */
@SuppressWarnings("DoNotCallSuggester") // the bodies of the skeleton, replaced by the chunk track
public final class SynxChunkLoader implements ChunkLoader {
    /** How far {@code view_bounds} reaches past {@code bounds}: the view distance cap 16, plus the one ring more the engine sends. */
    public static final int VIEW_MARGIN = 17;

    /**
     * Maps the region files of the release that {@code view_bounds} reaches; the caller has run the
     * start-up check of {@code API-M3-002} on {@code buildDir} and hands in the manifest it returned.
     *
     * @param bounds  the region this shard serves
     * @param overlay where chunks of the region load their overlay content from
     * @param tracker what the chunks tell about their overlay life
     * @throws IOException   if a region file cannot be mapped
     * @throws SynxException if a region file is no region file at all
     */
    public SynxChunkLoader(Path buildDir, SynxManifest manifest, SynxManifest.Bounds bounds, SynxOptions options,
                           OverlayIndex overlay, OverlayTracker tracker) throws IOException, SynxException {
        throw new UnsupportedOperationException("SynxChunkLoader is the M3-C step 0 skeleton");
    }

    @Override
    public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
        throw new UnsupportedOperationException("SynxChunkLoader is the M3-C step 0 skeleton");
    }

    @Override
    public void saveChunk(Chunk chunk) {
        throw new UnsupportedOperationException("SynxChunkLoader is the M3-C step 0 skeleton");
    }

    @Override
    public boolean supportsParallelLoading() {
        return true;
    }

    /** Counters since construction, and the current size of the section cache and the mappings. */
    public SynxStreamStats stats() {
        throw new UnsupportedOperationException("SynxChunkLoader is the M3-C step 0 skeleton");
    }
}
