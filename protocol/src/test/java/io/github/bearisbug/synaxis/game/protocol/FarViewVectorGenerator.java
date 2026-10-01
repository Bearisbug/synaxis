package io.github.bearisbug.synaxis.game.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Writes the frozen far view vectors of {@code docs/DESIGN-M3.md} §24 from {@link FarViewVectors}:
 * {@code ./gradlew :protocol:generateFarViewVectors}. It replaces every vector file and
 * {@code manifest.txt}, and leaves {@code README.md} alone.
 */
public final class FarViewVectorGenerator {
    private FarViewVectorGenerator() {
    }

    public static void main(String[] args) throws IOException {
        final Path dir = Path.of(args[0]);
        Files.createDirectories(dir.resolve("edge"));
        try (Stream<Path> old = Files.walk(dir)) {
            for (Path path : old.filter(Files::isRegularFile).toList()) {
                if (!path.getFileName().toString().equals("README.md")) Files.delete(path);
            }
        }
        final StringBuilder manifest = new StringBuilder()
                .append("pack_format_version ").append(FarViewPack.FORMAT_VERSION).append('\n')
                .append("announcement_format_version ").append(FarViewAnnouncement.FORMAT_VERSION).append('\n')
                .append("zlib ").append(FarViewVectors.zlibFingerprint()).append('\n');
        for (Map.Entry<String, byte[]> file : files().entrySet()) {
            Files.write(dir.resolve(file.getKey()), file.getValue());
            manifest.append(hex(file.getValue())).append("  ").append(file.getKey()).append('\n');
        }
        Files.writeString(dir.resolve("manifest.txt"), manifest.toString());
    }

    /** Every vector file by its path under the vectors directory, sorted by path. */
    static Map<String, byte[]> files() {
        final Map<String, byte[]> files = new TreeMap<>();
        FarViewVectors.announcements().forEach((name, announcement) -> files.put(name, FarViewVectors.encode(announcement)));
        final FarViewPack.Writer writer = FarViewVectors.packSmall();
        final byte[] pack = writer.write();
        files.put(FarViewVectors.PACK, pack);
        files.put(FarViewVectors.COARSE_PACK, writer.coarsePrefix());
        final FarViewPack.Header header;
        try {
            header = FarViewPack.Reader.read(pack).header();
        } catch (FarViewPack.Invalid e) {
            throw new IllegalStateException("the writer wrote a pack its reader refuses", e);
        }
        files.put(FarViewVectors.SAMPLES, FarViewVectors.samplesText(header).getBytes(StandardCharsets.UTF_8));
        for (Map<String, byte[]> edges : List.of(FarViewVectors.edgePacks(pack), FarViewVectors.edgeAnnouncements())) {
            edges.forEach((name, bytes) -> files.put("edge/" + name, bytes));
        }
        return files;
    }

    static String hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
