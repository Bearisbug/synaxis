package io.github.bearisbug.synaxis.synx;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * The fixed 256 byte header of a {@code .synx} file, API-M3-001. The offsets in {@link #encode()} and
 * {@link #decode} are the ones of the design table; the two change together.
 */
public record SynxHeader(int formatVersion, int flags, int protocolVersion, int compressionThreshold,
                         int compressionLevel, int minY, int height, int regionX, int regionZ,
                         byte[] registryDigest, byte[] contentHash, byte[] regionContentHash,
                         byte[] payloadSha256, int lightEngineVersion, int bakerVersion) {
    private static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    public SynxHeader {
        registryDigest = digest(registryDigest);
        contentHash = digest(contentHash);
        regionContentHash = digest(regionContentHash);
        payloadSha256 = digest(payloadSha256);
    }

    /** The header a region written with these settings carries. */
    public static SynxHeader of(SynxSettings settings, int regionX, int regionZ, byte[] contentHash,
                                byte[] regionContentHash, byte[] payloadSha256) {
        return new SynxHeader(SynxFormat.FORMAT_VERSION, settings.hasColumns() ? SynxFormat.FLAG_HAS_COLUMNS : 0,
                settings.protocolVersion(), settings.compressionThreshold(), settings.compressionLevel(),
                settings.minY(), settings.height(), regionX, regionZ, settings.registryDigest(), contentHash,
                regionContentHash, payloadSha256, settings.lightEngineVersion(), settings.bakerVersion());
    }

    private static byte[] digest(byte[] bytes) {
        if (bytes.length != 32) throw new IllegalArgumentException("digests are 32 bytes: " + bytes.length);
        return bytes.clone();
    }

    public boolean hasColumns() {
        return (flags & SynxFormat.FLAG_HAS_COLUMNS) != 0;
    }

    public byte[] encode() {
        final ByteBuffer buffer = ByteBuffer.allocate(SynxFormat.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0, SynxFormat.MAGIC);
        buffer.putShort(4, (short) formatVersion);
        buffer.putShort(6, (short) flags);
        buffer.putInt(8, protocolVersion);
        buffer.putInt(12, compressionThreshold);
        buffer.put(16, (byte) compressionLevel);
        // 17 padding, zero
        buffer.putShort(18, (short) minY);
        buffer.putShort(20, (short) height);
        // 22..23 padding, zero
        buffer.putInt(24, regionX);
        buffer.putInt(28, regionZ);
        buffer.put(32, registryDigest);
        buffer.put(64, contentHash);
        buffer.put(96, regionContentHash);
        buffer.put(128, payloadSha256);
        buffer.putShort(160, (short) lightEngineVersion);
        buffer.putShort(162, (short) bakerVersion);
        // 164..255 reserved, zero
        return buffer.array();
    }

    /**
     * Reads and checks the parts of the header that decide whether the rest can be read at all:
     * size, {@code magic}, {@code format_version} and {@code flags}.
     *
     * @param file the file name failure messages carry
     */
    public static SynxHeader decode(MemorySegment segment, String file) throws SynxException {
        if (segment.byteSize() < SynxFormat.HEADER_BYTES) {
            throw new SynxException(SynxError.TRUNCATED, file, segment.byteSize() + " bytes, the header alone is "
                    + SynxFormat.HEADER_BYTES);
        }
        final int magic = segment.get(INT, 0);
        if (magic != SynxFormat.MAGIC) {
            throw new SynxException(SynxError.MAGIC, file, "magic %08x, a .synx file starts with 53594e58 (SYNX)"
                    .formatted(Integer.reverseBytes(magic)));
        }
        final int formatVersion = Short.toUnsignedInt(segment.get(SHORT, 4));
        if (formatVersion != SynxFormat.FORMAT_VERSION) {
            throw new SynxException(SynxError.MAGIC, file, "format_version " + formatVersion
                    + ", this reader knows " + SynxFormat.FORMAT_VERSION);
        }
        final int flags = Short.toUnsignedInt(segment.get(SHORT, 6));
        if ((flags & ~SynxFormat.KNOWN_FLAGS) != 0) {
            throw new SynxException(SynxError.MAGIC, file, "flags " + Integer.toBinaryString(flags)
                    + ", this reader knows " + Integer.toBinaryString(SynxFormat.KNOWN_FLAGS));
        }
        return new SynxHeader(formatVersion, flags,
                segment.get(INT, 8), segment.get(INT, 12), segment.get(ValueLayout.JAVA_BYTE, 16),
                segment.get(SHORT, 18), Short.toUnsignedInt(segment.get(SHORT, 20)),
                segment.get(INT, 24), segment.get(INT, 28),
                bytes(segment, 32), bytes(segment, 64), bytes(segment, 96), bytes(segment, 128),
                Short.toUnsignedInt(segment.get(SHORT, 160)), Short.toUnsignedInt(segment.get(SHORT, 162)));
    }

    private static byte[] bytes(MemorySegment segment, long offset) {
        return segment.asSlice(offset, 32).toArray(ValueLayout.JAVA_BYTE);
    }

    @Override
    public byte[] registryDigest() {
        return registryDigest.clone();
    }

    @Override
    public byte[] contentHash() {
        return contentHash.clone();
    }

    @Override
    public byte[] regionContentHash() {
        return regionContentHash.clone();
    }

    @Override
    public byte[] payloadSha256() {
        return payloadSha256.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SynxHeader other && Arrays.equals(encode(), other.encode());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encode());
    }

    @Override
    public String toString() {
        return "SynxHeader[v" + formatVersion + ", flags " + flags + ", protocol " + protocolVersion
                + ", region " + regionX + "," + regionZ + ", payload " + SynxDigests.hex(payloadSha256) + "]";
    }
}
