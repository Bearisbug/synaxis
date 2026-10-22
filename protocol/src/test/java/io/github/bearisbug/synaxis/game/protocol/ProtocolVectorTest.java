package io.github.bearisbug.synaxis.game.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The frozen wire vectors of {@code docs/DESIGN-M2.md} §24 and {@code docs/TEST.md} TC-GAME-017.
 * <p>
 * Every message has a canonical vector in {@code vectors/} and a boundary one in
 * {@code vectors/edge/}; the client mod runs this very same directory in §26 stage 6a. Two things
 * are asserted: each vector decodes into the record written down here and re-encodes byte for
 * byte, and the digest of the whole set matches the one frozen in {@code manifest.txt} next to the
 * {@code schema_version} it belongs to — that is what makes "change a layout, raise
 * {@link ProtocolSchema#SCHEMA_VERSION} in the same change" a red build instead of a convention.
 */
class ProtocolVectorTest {

    private static final Function<MsgBuf, Message> HANDSHAKE = HandshakeMessages::read;

    /**
     * One frozen vector: the file below {@code vectors/}, the record it must decode into and the
     * channel decoder that reads it.
     */
    record Vector(String file, Message expected, Function<MsgBuf, Message> reader) {
        @Override
        public String toString() {
            return file;
        }
    }

    static List<Vector> vectors() {
        return List.of(
                new Vector("c2s_hello.bin", new C2SHello(1, ProtocolSchema.SCHEMA_VERSION), HANDSHAKE),
                new Vector("s2c_hello.bin", new S2CHello(ProtocolSchema.SCHEMA_VERSION, 20, 0), HANDSHAKE),

                new Vector("edge/c2s_hello.bin", new C2SHello(Integer.MAX_VALUE, Integer.MAX_VALUE), HANDSHAKE),
                new Vector("edge/s2c_hello.bin", new S2CHello(ProtocolSchema.SCHEMA_VERSION, 255, Integer.MAX_VALUE), HANDSHAKE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void aFrozenVectorDecodesAndReEncodesByteForByte(Vector vector) throws IOException {
        final byte[] frozen = Files.readAllBytes(vectorsDirectory().resolve(vector.file()));

        final MsgBuf reader = MsgBuf.reading(frozen);
        assertEquals(vector.expected(), vector.reader().apply(reader), vector.file());
        assertEquals(0, reader.remaining(), vector.file() + " has bytes the decoder never reads");

        final MsgBuf writer = MsgBuf.writing();
        vector.expected().write(writer);
        assertArrayEquals(frozen, writer.toByteArray(), vector.file());
    }

    @Test
    void everyMessageHasACanonicalAndABoundaryVector() throws IOException {
        final List<String> canonical = binFiles(vectorsDirectory());
        final List<String> edge = binFiles(vectorsDirectory().resolve("edge"));
        assertEquals(2, canonical.size(), canonical.toString());
        assertEquals(canonical, edge, "the boundary set must cover the same two messages");
        assertEquals(vectors().size(), canonical.size() + edge.size());
    }

    @Test
    void theFrozenVectorsAreBoundToTheSchemaVersion() throws IOException {
        final List<String> manifest = Files.readAllLines(vectorsDirectory().resolve("manifest.txt"), StandardCharsets.UTF_8);
        final String frozenVersion = value(manifest, "schema_version");
        final String frozenDigest = value(manifest, "digest");

        assertEquals(String.valueOf(ProtocolSchema.SCHEMA_VERSION), frozenVersion,
                "manifest.txt froze the vectors of another schema_version");
        assertEquals(frozenDigest, digestOfVectors(), """
                线格式变了：按 docs/DESIGN-M2.md §24，改消息布局必须在同一个改动里把 \
                ProtocolSchema.SCHEMA_VERSION 加 1、重新生成 vectors/ 与 vectors/edge/ 下的 .bin，\
                并把新的 schema_version 与 digest 写进 manifest.txt""");
    }

    private static String value(List<String> manifest, String key) {
        return manifest.stream()
                .filter(line -> line.startsWith(key + "="))
                .map(line -> line.substring(key.length() + 1).trim())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("manifest.txt has no " + key));
    }

    private static String digestOfVectors() throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (final String file : allBinFiles()) {
            digest.update(file.getBytes(StandardCharsets.UTF_8));
            digest.update(Files.readAllBytes(vectorsDirectory().resolve(file)));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<String> allBinFiles() throws IOException {
        final List<String> files = new ArrayList<>(binFiles(vectorsDirectory()));
        for (final String edge : binFiles(vectorsDirectory().resolve("edge"))) files.add("edge/" + edge);
        return files;
    }

    private static List<String> binFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".bin"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    private static Path vectorsDirectory() {
        final URL url = ProtocolVectorTest.class.getResource("/vectors/manifest.txt");
        if (url == null) throw new IllegalStateException("the frozen vectors are missing from the test resources");
        try {
            return Path.of(url.toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
