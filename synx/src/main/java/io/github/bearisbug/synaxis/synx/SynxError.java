package io.github.bearisbug.synaxis.synx;

/**
 * Why a shard refuses to start on a map release, {@code docs/DESIGN-M3.md} §14. The code is what
 * the start-up log carries and what the runbooks key on.
 */
public enum SynxError {
    /** Unknown {@code magic} or {@code format_version}, an unknown {@code flags} bit, or not a region file. */
    MAGIC("synx-magic"),
    /** The file ends inside the header or the offset index. */
    TRUNCATED("synx-truncated"),
    PROTOCOL_MISMATCH("synx-protocol-mismatch"),
    REGISTRY_MISMATCH("synx-registry-mismatch"),
    COMPRESSION_MISMATCH("synx-compression-mismatch"),
    /** {@code min_y} or {@code height} differs from the running process. */
    WORLD_MISMATCH("synx-world-mismatch"),
    /** {@code payload_sha256} differs from the manifest or from the bytes of the file. */
    PAYLOAD_HASH("synx-payload-hash"),
    LIGHT_ENGINE_MISMATCH("synx-light-engine-mismatch"),
    /** {@code manifest.json} is missing or unreadable, or disagrees with its directory or region files. */
    MANIFEST("synx-manifest");

    private final String code;

    SynxError(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
