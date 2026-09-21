package io.github.bearisbug.synaxis.game.client;

import io.github.bearisbug.synaxis.game.protocol.ArchetypeBox;
import io.github.bearisbug.synaxis.game.protocol.HandshakeMessages;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.ProtocolSchema;
import io.netty.buffer.Unpooled;
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
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The client half of the two ended guarantee of {@code docs/DESIGN-M2.md} §24.
 * <p>
 * The files read here are not a copy: the build points {@code synaxis.vectors.dir} at
 * {@code protocol/src/test/resources/vectors}, the very directory the server's
 * {@code ProtocolVectorTest} runs, so both ends decode the same bytes and re-encode them. The
 * server's copy is the one that names the expected record of every vector; this one asserts what
 * only the client can: that the bytes survive the whole client path — the {@code :protocol} jar
 * this build resolved from the local repository, wrapped in Minecraft's own payload buffer.
 * <p>
 * That is also what makes it more than a second run of the same assertions. The client consumes
 * {@code :protocol} as a published artifact across two Gradle builds ({@code ADR-M2-015}), so it
 * can be holding a jar older than the vectors in the working tree — the one drift a server side
 * test cannot see. {@link #theVectorsAreTheOnesThisBuildsProtocolJarSpeaks()} binds the two
 * together through {@code schema_version} and the frozen digest.
 * <p>
 * The division of labour with the server's copy is deliberate. A decode followed by an encode is
 * self consistent for any bytes at all, so this side cannot tell a vector that changed from one
 * that did not; the expected record of every vector is written down once, in
 * {@code ProtocolVectorTest}, and the content of the set is held here by the digest instead.
 */
class FrozenVectorTest {

    /** Nine messages in the canonical set, the same nine in {@code edge/}, plus the box table. */
    private static final int VECTOR_COUNT = 19;

    /** The one frozen vector that is not a message: the archetype box table of §9. */
    private static final String ARCHETYPE_BOX = "archetype_box.bin";

    /** The two vectors of the {@code synaxis:handshake} channel; the rest are {@code rts:game}. */
    private static final List<String> HANDSHAKE = List.of("c2s_hello.bin", "s2c_hello.bin");

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void aFrozenVectorSurvivesTheClientCodecByteForByte(String file) throws IOException {
        final byte[] frozen = Files.readAllBytes(vectorsDirectory().resolve(file));

        assertArrayEquals(frozen, reEncode(file, frozen), file);
    }

    /**
     * Binds the vectors to the {@code :protocol} jar this build actually resolved.
     * <p>
     * A client holding a stale jar is the cross build version of the drift §24 is about: the mod
     * would keep shipping an older layout while the server moved on, and nothing on the wire would
     * say so until a player joined. The frozen digest plus {@code schema_version} turn that into a
     * red build here.
     */
    @Test
    void theVectorsAreTheOnesThisBuildsProtocolJarSpeaks() throws IOException {
        final List<String> manifest =
                Files.readAllLines(vectorsDirectory().resolve("manifest.txt"), StandardCharsets.UTF_8);

        assertEquals(VECTOR_COUNT, vectors().size(), "the frozen set is not the one §24 describes");
        assertEquals(String.valueOf(ProtocolSchema.SCHEMA_VERSION), value(manifest, "schema_version"), """
                客户端消费的 :protocol 与冻结向量不是同一版：按 docs/DESIGN-M2.md §24，先在主构建跑 \
                publishToMavenLocal 再构建模组；若两者本就不同版，说明 ADR-M2-015 的跨构建消费断了""");
        assertEquals(value(manifest, "digest"), digestOfVectors(), """
                冻结向量的字节与 manifest.txt 的 digest 对不上：服务端侧的 ProtocolVectorTest 会红在同一处，\
                按 §24 升 SCHEMA_VERSION 并重新冻结整套向量""");
    }

    static List<String> vectors() throws IOException {
        final List<String> files = new ArrayList<>(binFiles(vectorsDirectory()));
        for (final String edge : binFiles(vectorsDirectory().resolve("edge"))) files.add("edge/" + edge);
        return List.copyOf(files);
    }

    /** Reads one vector the way the client reads it off the wire and writes it back out. */
    private static byte[] reEncode(String file, byte[] frozen) {
        final String name = file.startsWith("edge/") ? file.substring("edge/".length()) : file;

        if (ARCHETYPE_BOX.equals(name)) {
            // Held by both ends and carried by no message (§9): the client's copy is this table.
            final MsgBuf out = MsgBuf.writing();
            ArchetypeBox.writeTable(out);
            return out.toByteArray();
        }
        if (HANDSHAKE.contains(name)) {
            // synaxis:handshake has no payload type on the client yet (REQ-M2-020a registered the
            // play channel only), so this goes straight through the shared decoder.
            final MsgBuf out = MsgBuf.writing();
            HandshakeMessages.read(MsgBuf.reading(frozen)).write(out);
            return out.toByteArray();
        }

        final RegistryFriendlyByteBuf inbound = buffer();
        inbound.writeBytes(frozen);
        final GamePayload payload = GamePayload.CODEC.decode(inbound);
        assertNotNull(payload.message(), file + " decoded to an unknown msg_id");

        final RegistryFriendlyByteBuf outbound = buffer();
        GamePayload.CODEC.encode(outbound, payload);
        final byte[] bytes = new byte[outbound.readableBytes()];
        outbound.readBytes(bytes);
        return bytes;
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    /** The same digest the server computes: every {@code .bin} by relative path, then its bytes. */
    private static String digestOfVectors() throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (final String file : vectors()) {
            digest.update(file.getBytes(StandardCharsets.UTF_8));
            digest.update(Files.readAllBytes(vectorsDirectory().resolve(file)));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String value(List<String> manifest, String key) {
        return manifest.stream()
                .filter(line -> line.startsWith(key + "="))
                .map(line -> line.substring(key.length() + 1).trim())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("manifest.txt has no " + key));
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
        final String directory = System.getProperty("synaxis.vectors.dir");
        if (directory == null) throw new IllegalStateException("the build did not say where the frozen vectors are");
        final Path path = Path.of(directory);
        if (!Files.isDirectory(path)) throw new IllegalStateException("the frozen vectors are not at " + path);
        return path;
    }
}
