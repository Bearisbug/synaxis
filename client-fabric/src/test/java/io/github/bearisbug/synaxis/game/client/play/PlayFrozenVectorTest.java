package io.github.bearisbug.synaxis.game.client.play;

import io.github.bearisbug.synaxis.game.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.play.PlayMessage;
import io.github.bearisbug.synaxis.game.protocol.play.PlayMessages;
import io.github.bearisbug.synaxis.game.protocol.play.PlayProtocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The client half of the {@code synaxis:play} vectors ({@code docs/DESIGN-PLAY.md} §24, {@code docs/TEST.md}
 * TC-PLAY-009).
 * <p>
 * The build points {@code synaxis.play-vectors.dir} at {@code protocol/src/test/resources/play-vectors}, the
 * directory the server's {@code PlayVectorTest} runs, so both ends read the same bytes. The expected record of
 * every vector is written down once, on the server side; what this side adds is the jar: the mod consumes
 * {@code :protocol} as a published artifact across two Gradle builds ({@code ADR-M2-015}), so it can hold a
 * jar older than the vectors in the working tree. {@code play_version} and the digest in {@code manifest.txt}
 * bind the two, then every vector goes through that jar's decoder: an accepted one re-encodes byte for byte,
 * a {@code refused-} one is malformed. Nothing here counts the vectors, so adding one needs no change.
 */
class PlayFrozenVectorTest {

    /** The prefix of the vectors both ends must refuse as malformed. */
    private static final String REFUSED = "refused-";

    @Test
    void theVectorsAreTheOnesThisBuildsProtocolJarSpeaks() throws IOException {
        final List<String> manifest = Files.readAllLines(directory().resolve("manifest.txt"), StandardCharsets.UTF_8);

        assertEquals(String.valueOf(PlayProtocol.version()), value(manifest, "play_version"), """
                客户端消费的 :protocol 与 synaxis:play 向量不是同一版：先在主构建跑 publishToMavenLocal 再构建模组\
                （docs/DESIGN-PLAY.md §24）""");
        assertEquals(value(manifest, "digest"), digest(), """
                play-vectors/ 的字节与 manifest.txt 的 digest 对不上：服务端的 PlayVectorTest 会红在同一处，\
                按 docs/DESIGN-PLAY.md §24 重新冻结""");
        assertFalse(accepted().isEmpty());
        assertFalse(refused().isEmpty());
    }

    static List<String> accepted() throws IOException {
        return files().stream().filter(file -> !isRefused(file)).toList();
    }

    static List<String> refused() throws IOException {
        return files().stream().filter(PlayFrozenVectorTest::isRefused).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("accepted")
    void anAcceptedVectorSurvivesTheClientDecoderByteForByte(String file) throws IOException {
        final byte[] frozen = Files.readAllBytes(directory().resolve(file));

        final MsgBuf reader = MsgBuf.reading(frozen);
        final PlayMessage message = PlayMessages.read(reader);
        assertEquals(0, reader.remaining(), file + " has bytes the decoder never reads");
        final MsgBuf writer = MsgBuf.writing();
        message.write(writer);
        assertArrayEquals(frozen, writer.toByteArray(), file);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refused")
    void aRefusedVectorIsMalformedOnTheClient(String file) throws IOException {
        final byte[] frozen = Files.readAllBytes(directory().resolve(file));

        assertThrows(MalformedMessageException.class, () -> PlayMessages.read(MsgBuf.reading(frozen)), file);
    }

    private static boolean isRefused(String file) {
        return file.startsWith("edge/" + REFUSED);
    }

    /** The digest the server computes: the root's {@code .bin} files by name, then {@code edge/}'s, path then bytes. */
    private static String digest() throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (final String file : files()) {
            digest.update(file.getBytes(StandardCharsets.UTF_8));
            digest.update(Files.readAllBytes(directory().resolve(file)));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<String> files() throws IOException {
        final List<String> files = new ArrayList<>(binFiles(directory()));
        for (final String edge : binFiles(directory().resolve("edge"))) files.add("edge/" + edge);
        return List.copyOf(files);
    }

    private static List<String> binFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".bin"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    private static String value(List<String> manifest, String key) {
        return manifest.stream()
                .filter(line -> line.startsWith(key + "="))
                .map(line -> line.substring(key.length() + 1).trim())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("manifest.txt has no " + key));
    }

    private static Path directory() {
        final String directory = System.getProperty("synaxis.play-vectors.dir");
        if (directory == null) throw new IllegalStateException("the build did not say where the synaxis:play vectors are");
        final Path path = Path.of(directory);
        if (!Files.isDirectory(path)) throw new IllegalStateException("the synaxis:play vectors are not at " + path);
        return path;
    }
}
