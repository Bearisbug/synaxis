package io.github.bearisbug.synaxis.synx;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Writes the frozen vectors of {@code docs/DESIGN-M3.md} §24 from {@link SynxVectors}:
 * {@code ./gradlew :synx:generateVectors}. The regular vectors come out of the writer; each edge
 * vector is a regular one with a few bytes changed, as {@link #edges} spells out.
 */
public final class SynxVectorGenerator {
    private SynxVectorGenerator() {
    }

    public static void main(String[] args) throws IOException {
        final Path dir = Path.of(args[0]);
        Files.createDirectories(dir.resolve("edge"));
        final Map<String, byte[]> files = files();
        try (Stream<Path> old = Files.walk(dir)) {
            for (Path path : old.filter(Files::isRegularFile).toList()) {
                final String name = dir.relativize(path).toString().replace('\\', '/');
                if (name.endsWith(".synx") || name.equals(SynxManifest.FILE_NAME)) Files.delete(path);
            }
        }
        final StringBuilder manifest = new StringBuilder()
                .append("format_version ").append(SynxFormat.FORMAT_VERSION).append('\n')
                .append("zlib ").append(SynxVectors.zlibFingerprint()).append('\n');
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Files.write(dir.resolve(file.getKey()), file.getValue());
            manifest.append(SynxDigests.hex(SynxDigests.sha256(file.getValue()))).append("  ").append(file.getKey()).append('\n');
        }
        Files.writeString(dir.resolve("manifest.txt"), manifest.toString());
    }

    /** Every vector file by its path under the vectors directory, sorted by path. */
    static Map<String, byte[]> files() {
        final SynxSettings settings = SynxVectors.settings();
        final List<SynxRegionWriter.Encoded> encoded = new ArrayList<>();
        for (SynxVectors.Vector vector : SynxVectors.vectors()) encoded.add(vector.encode(settings));
        final byte[] contentHash = SynxVectors.contentHash(encoded);

        final Map<String, byte[]> files = new TreeMap<>();
        for (int i = 0; i < encoded.size(); i++) {
            files.put(SynxVectors.vectors().get(i).name() + ".synx", encoded.get(i).toBytes(contentHash));
        }
        files.put(SynxManifest.FILE_NAME,
                SynxVectors.manifest(encoded, contentHash).toJson().getBytes(StandardCharsets.UTF_8));
        edges(files.get("one-chunk-flat.synx"), files.get("two-by-two-lit.synx"))
                .forEach((name, bytes) -> files.put("edge/" + name + ".synx", bytes));
        return files;
    }

    static Map<String, byte[]> edges(byte[] flat, byte[] lit) {
        final Map<String, byte[]> edges = new TreeMap<>();
        // Ends inside the offset index: the file cannot be opened
        edges.put("truncated-index", Arrays.copyOf(flat, 4096));
        // Ends inside the third record of the lit region (index order (-2,0), (-1,0), (-2,1), (-1,1)):
        // the first two chunks read, the last two are out of range, the payload hash no longer holds
        final long third = entry(lit, SynxFormat.index(-2, 1)).getLong(0);
        edges.put("truncated-records", Arrays.copyOf(lit, (int) third + 10));
        // The index entry of (-1,1) points one aligned step past the end of the file
        final byte[] outOfRange = lit.clone();
        entry(outOfRange, SynxFormat.index(-1, 1)).putLong(0, lit.length + 8L);
        edges.put("offset-out-of-range", outOfRange);
        // One byte of segment ② of (-2,1) flipped: that record fails its crc32c
        final byte[] crc = lit.clone();
        crc[(int) third + SynxFormat.SEGMENT_TABLE_BYTES
                + ByteBuffer.wrap(lit, (int) third, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() + 7] ^= 0x01;
        edges.put("crc-mismatch", crc);
        // payload_sha256 of the header flipped: every record reads, the start-up check refuses
        final byte[] payload = flat.clone();
        payload[128] ^= 0x01;
        edges.put("payload-hash", payload);
        final byte[] protocol = flat.clone();
        ByteBuffer.wrap(protocol).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 775);
        edges.put("protocol-775", protocol);
        final byte[] registry = flat.clone();
        registry[32] ^= 0x01;
        edges.put("registry-mismatch", registry);
        final byte[] flag = flat.clone();
        flag[6] |= 0x04;
        edges.put("unknown-flag", flag);
        return edges;
    }

    private static ByteBuffer entry(byte[] file, int index) {
        return ByteBuffer.wrap(file, SynxFormat.HEADER_BYTES + index * SynxFormat.INDEX_ENTRY_BYTES,
                SynxFormat.INDEX_ENTRY_BYTES).slice().order(ByteOrder.LITTLE_ENDIAN);
    }
}
