package io.github.bearisbug.synaxis.synx;

import java.util.Arrays;
import java.util.Objects;

/**
 * What every region of one map release is encoded with; the header of each file records it.
 *
 * @param protocolVersion      the protocol segment ① is framed for (776)
 * @param compressionThreshold the threshold segment ① is framed with, at least 1; the process has to use the same
 * @param compressionLevel     the {@link java.util.zip.Deflater} level, -1 for the default
 * @param minY                 the lowest block y of the world
 * @param height               the world height in blocks, a multiple of 16
 * @param registryDigest       {@link SynxDigests#registryDigest}, 32 bytes
 * @param lightEngineVersion   {@code LightEngine.VERSION} the light was computed with
 * @param bakerVersion         the version of the baker that wrote the file
 * @param hasColumns           whether every record carries segment ④
 */
public record SynxSettings(int protocolVersion, int compressionThreshold, int compressionLevel,
                           int minY, int height, byte[] registryDigest,
                           int lightEngineVersion, int bakerVersion, boolean hasColumns) {
    public SynxSettings {
        if (height <= 0 || height % 16 != 0 || height > 0xFFFF) {
            throw new IllegalArgumentException("height must be a positive multiple of 16: " + height);
        }
        if (minY % 16 != 0 || minY < Short.MIN_VALUE || minY > Short.MAX_VALUE) {
            throw new IllegalArgumentException("min_y must be a multiple of 16 that fits an i16: " + minY);
        }
        if (compressionThreshold < 1) {
            throw new IllegalArgumentException("segment ① is always framed for a compressed connection, threshold >= 1: "
                    + compressionThreshold);
        }
        if (compressionLevel < -1 || compressionLevel > 9) {
            throw new IllegalArgumentException("compression level out of range: " + compressionLevel);
        }
        if (registryDigest.length != 32) {
            throw new IllegalArgumentException("registry digest must be 32 bytes: " + registryDigest.length);
        }
        if (lightEngineVersion < 0 || lightEngineVersion > 0xFFFF || bakerVersion < 0 || bakerVersion > 0xFFFF) {
            throw new IllegalArgumentException("versions are u16");
        }
        registryDigest = registryDigest.clone();
    }

    @Override
    public byte[] registryDigest() {
        return registryDigest.clone();
    }

    public int sectionCount() {
        return height / 16;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SynxSettings other
                && protocolVersion == other.protocolVersion && compressionThreshold == other.compressionThreshold
                && compressionLevel == other.compressionLevel && minY == other.minY && height == other.height
                && Arrays.equals(registryDigest, other.registryDigest)
                && lightEngineVersion == other.lightEngineVersion && bakerVersion == other.bakerVersion
                && hasColumns == other.hasColumns;
    }

    @Override
    public int hashCode() {
        return Objects.hash(protocolVersion, compressionThreshold, compressionLevel, minY, height,
                Arrays.hashCode(registryDigest), lightEngineVersion, bakerVersion, hasColumns);
    }
}
