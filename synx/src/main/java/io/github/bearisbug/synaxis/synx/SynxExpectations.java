package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.registry.Registries;

import java.util.Arrays;

/**
 * What the running process needs a map release to match before it starts on it (API-M3-001 read
 * rules, API-M3-002 start-up check).
 */
public record SynxExpectations(int protocolVersion, byte[] registryDigest, int compressionThreshold,
                               int minY, int height, int lightEngineVersion) {
    public SynxExpectations {
        registryDigest = registryDigest.clone();
    }

    /** The expectations of this process: its protocol and the digest of the given registries. */
    public static SynxExpectations of(Registries registries, int compressionThreshold, int minY, int height,
                                      int lightEngineVersion) {
        return new SynxExpectations(MinecraftServer.PROTOCOL_VERSION, SynxDigests.registryDigest(registries),
                compressionThreshold, minY, height, lightEngineVersion);
    }

    @Override
    public byte[] registryDigest() {
        return registryDigest.clone();
    }

    /**
     * Compares a region header with this process: protocol version, registry digest, compression
     * threshold, world, light engine version (API-M3-001 读取规则); the first that differs throws.
     *
     * @param file the file name the failure message carries
     */
    public void check(SynxHeader header, String file) throws SynxException {
        if (header.protocolVersion() != protocolVersion) {
            throw new SynxException(SynxError.PROTOCOL_MISMATCH, file,
                    "protocol_version " + header.protocolVersion() + ", process " + protocolVersion);
        }
        if (!Arrays.equals(header.registryDigest(), registryDigest)) {
            throw new SynxException(SynxError.REGISTRY_MISMATCH, file,
                    "registry_digest " + SynxDigests.hex(header.registryDigest()) + ", process " + SynxDigests.hex(registryDigest));
        }
        if (header.compressionThreshold() != compressionThreshold) {
            throw new SynxException(SynxError.COMPRESSION_MISMATCH, file,
                    "compression_threshold " + header.compressionThreshold() + ", process " + compressionThreshold
                            + " (synaxis.compression-threshold)");
        }
        if (header.minY() != minY || header.height() != height) {
            throw new SynxException(SynxError.WORLD_MISMATCH, file,
                    "min_y " + header.minY() + " height " + header.height() + ", the manifest says " + minY + " " + height);
        }
        if (header.lightEngineVersion() != lightEngineVersion) {
            throw new SynxException(SynxError.LIGHT_ENGINE_MISMATCH, file,
                    "light_engine_version " + header.lightEngineVersion() + ", process " + lightEngineVersion);
        }
    }
}
