package io.github.bearisbug.synaxis.synx;

/**
 * Why a map release is refused, {@code docs/DESIGN-M3.md} §14: a shard does not start on it, the
 * baker's verify fails it. The code is what the start-up log carries and what the runbooks key on.
 */
public enum SynxError {
    /**
     * Unknown {@code magic} or {@code format_version}, an unknown {@code flags} bit, or a file of the other
     * kind: an overlay segment where a region file is expected, or the reverse.
     */
    MAGIC("synx-magic"),
    /** The file ends inside the header or the offset index. */
    TRUNCATED("synx-truncated"),
    /** {@code mc_version} or {@code protocol_version} differs from the running process. */
    PROTOCOL_MISMATCH("synx-protocol-mismatch"),
    REGISTRY_MISMATCH("synx-registry-mismatch"),
    /**
     * {@code compression_threshold} of a file differs from {@code synaxis.compression-threshold}; the baker
     * always writes 256.
     */
    COMPRESSION_MISMATCH("synx-compression-mismatch"),
    /** {@code min_y} or {@code height} of a region or overlay segment header differs from the manifest {@code world}. */
    WORLD_MISMATCH("synx-world-mismatch"),
    /**
     * The file size or {@code payload_sha256} differs from the manifest, or {@code payload_sha256} from the
     * bytes of the file.
     */
    PAYLOAD_HASH("synx-payload-hash"),
    LIGHT_ENGINE_MISMATCH("synx-light-engine-mismatch"),
    /**
     * {@code manifest.json} is missing or unreadable, or disagrees with its directory or region files; an
     * overlay segment of another release.
     */
    MANIFEST("synx-manifest");

    private final String code;

    SynxError(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
