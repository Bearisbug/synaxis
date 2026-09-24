package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.synx.SynxManifest;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * The input of a bake (API-M3-003): an edit source save or a generator. {@link #read} may be called from
 * several threads at once, and more than once for the same chunk: the baker reads a ring of one chunk
 * around every region, and again for the chunks it samples in its verification.
 */
public interface BakeSource extends AutoCloseable {
    /** {@code anvil} or {@code generator}, the manifest's {@code source.kind}. */
    String kind();

    int minY();

    /** World height in blocks, a multiple of 16. */
    int height();

    /** The chunks of the map, inclusive; the baker writes a record for every one of them. */
    SynxManifest.Bounds bounds();

    /** The source DataVersion for the manifest, null for a generator. */
    @Nullable Integer dataVersion();

    /** The share of chunks whose source light is complete, known after {@link #checkGate}; null for a generator. */
    @Nullable Double lightCoverage();

    /**
     * Refuses the source before anything is written: every chunk inside {@link #bounds()} must be readable and
     * acceptable (for an Anvil save: DataVersion, status full, markers mappable).
     *
     * @throws io.github.bearisbug.synaxis.baker.anvil.AnvilSourceException naming the first chunk that is not
     */
    void checkGate(int threads) throws IOException;

    /** One chunk inside {@link #bounds()}. */
    SourceChunk read(int chunkX, int chunkZ) throws IOException;

    @Override
    void close() throws IOException;
}
