package io.github.bearisbug.synaxis.game.protocol;

import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Layer;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Sample;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security review 2026-10 (surface 2): the far view decoders against malformed bytes. Two cases
 * mutate a valid pack or announcement and require the decoder to either read it or refuse it with
 * its own checked type, any other throwable being a finding; the third holds an announced object
 * to 40 MiB.
 */
class FarViewDecoderBoundaryTest {

    private static final List<String> BIOMES = List.of("minecraft:forest", "minecraft:plains", "minecraft:river");
    private static final int MUTATIONS = 20_000;

    private static byte[] pack() {
        final List<Sample> slope = new ArrayList<>(FarViewPack.BLOCK_SAMPLES);
        for (int z = 0; z < 64; z++) {
            for (int x = 0; x < 64; x++) slope.add(new Sample(9, 0, (x * 3 + z) % 200, 0, 0, (x + z) % 3));
        }
        final FarViewPack.Writer writer = new FarViewPack.Writer(2, -1, 0, -64, 256, 32_366, 776, BIOMES);
        writer.block(Layer.COARSE, 0, 0, slope).block(Layer.COARSE, 3, 3, Collections.nCopies(4096, Sample.EMPTY));
        writer.block(Layer.FINE, 0, 0, slope).block(Layer.FINE, 15, 15, slope);
        return writer.write();
    }

    private static byte[] mutate(byte[] original, Random random) {
        final byte[] bytes;
        switch (random.nextInt(4)) {
            case 0 -> {
                bytes = original.clone();
                bytes[random.nextInt(bytes.length)] ^= (byte) (1 << random.nextInt(8));
            }
            case 1 -> {
                bytes = original.clone();
                // The header and the indexes, where every offset, length and count is
                final int at = random.nextInt(Math.min(bytes.length - 4, 400));
                final int value = switch (random.nextInt(4)) {
                    case 0 -> -1;
                    case 1 -> Integer.MAX_VALUE;
                    case 2 -> Integer.MIN_VALUE;
                    default -> random.nextInt();
                };
                bytes[at] = (byte) value;
                bytes[at + 1] = (byte) (value >>> 8);
                bytes[at + 2] = (byte) (value >>> 16);
                bytes[at + 3] = (byte) (value >>> 24);
            }
            case 2 -> bytes = Arrays.copyOf(original, random.nextInt(original.length));
            default -> {
                bytes = original.clone();
                final int from = random.nextInt(bytes.length);
                final int to = Math.min(bytes.length, from + 1 + random.nextInt(64));
                for (int i = from; i < to; i++) bytes[i] = (byte) random.nextInt();
            }
        }
        return bytes;
    }

    @Test
    void mutatedPacksAreReadOrRefusedAsInvalid() {
        final byte[] valid = pack();
        final Random random = new Random(20261002L);
        final List<String> uncontrolled = new ArrayList<>();
        final int[] refused = {0};
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (int i = 0; i < MUTATIONS; i++) {
                final byte[] bytes = mutate(valid, random);
                try {
                    final FarViewPack.Reader reader = FarViewPack.Reader.read(bytes);
                    reader.verify();
                } catch (FarViewPack.Invalid _) {
                    refused[0]++;
                } catch (Throwable t) {
                    if (uncontrolled.size() < 10) uncontrolled.add(i + ": " + t);
                }
            }
        });
        System.out.println("FarViewPack.Reader: " + MUTATIONS + " mutations, " + refused[0] + " refused as Invalid, "
                + uncontrolled.size() + " uncontrolled");
        assertEquals(List.of(), uncontrolled);
        assertTrue(refused[0] > MUTATIONS / 2, "most mutations must be refused: " + refused[0]);
    }

    private static byte[] announcementVector(String name) throws IOException, URISyntaxException {
        final URL url = Objects.requireNonNull(FarViewDecoderBoundaryTest.class.getResource("/farview-vectors/" + name));
        return Files.readAllBytes(Path.of(url.toURI()));
    }

    @Test
    void mutatedAnnouncementsAreReadOrRefusedAsMalformed() throws Exception {
        final byte[] valid = announcementVector("announce-nine-tiles.bin");
        final Random random = new Random(20261003L);
        final List<String> uncontrolled = new ArrayList<>();
        final int[] refused = {0};
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (int i = 0; i < MUTATIONS; i++) {
                final byte[] bytes = mutate(valid, random);
                try {
                    FarViewAnnouncement.read(MsgBuf.reading(bytes));
                } catch (MalformedMessageException _) {
                    refused[0]++;
                } catch (Throwable t) {
                    if (uncontrolled.size() < 10) uncontrolled.add(i + ": " + t);
                }
            }
        });
        System.out.println("FarViewAnnouncement.read: " + MUTATIONS + " mutations, " + refused[0]
                + " refused as malformed, " + uncontrolled.size() + " uncontrolled");
        assertEquals(List.of(), uncontrolled);
    }

    /** One whole tile of {@code bytes}, written field by field so that it can say what the record refuses to. */
    private static byte[] announcementOf(long bytes) {
        final MsgBuf buf = MsgBuf.writing();
        buf.writeVarInt(FarViewAnnouncement.FORMAT_VERSION);
        buf.writeString("b");
        buf.writeUnsignedByte(2);
        buf.writeUnsignedByte(FarViewPack.COARSE_LEVEL);
        buf.writeVarInt(-64);
        buf.writeVarInt(384);
        buf.writeVarInt(1);
        buf.writeVarInt(Integer.MIN_VALUE);
        buf.writeVarInt(Integer.MAX_VALUE);
        buf.writeUnsignedByte(FarViewAnnouncement.LAYER_FULL);
        buf.writeVarLong(bytes);
        buf.writeBytes(new byte[FarViewAnnouncement.SHA256_BYTES]);
        buf.writeString("https://cdn.example/x.hfp");
        return buf.toByteArray();
    }

    /**
     * {@code bytes} of a tile is held to 40 MiB where the tile is built and where it is read, so an
     * announcement cannot have the client download more than that per object.
     */
    @Test
    void anAnnouncedObjectSizeIsHeldTo40MiB() {
        final byte[] digest = new byte[FarViewAnnouncement.SHA256_BYTES];
        final FarViewAnnouncement.Tile tile = new FarViewAnnouncement.Tile(Integer.MIN_VALUE, Integer.MAX_VALUE,
                FarViewAnnouncement.LAYER_FULL, 41_943_040L, digest, "https://cdn.example/x.hfp");
        final FarViewAnnouncement announcement = new FarViewAnnouncement("b", 2, FarViewPack.COARSE_LEVEL, -64, 384,
                List.of(tile));
        final MsgBuf out = MsgBuf.writing();
        announcement.write(out);
        assertArrayEquals(announcementOf(41_943_040L), out.toByteArray());
        assertEquals(41_943_040L, FarViewAnnouncement.read(MsgBuf.reading(out.toByteArray())).tiles().getFirst().bytes());
        for (long bytes : new long[]{41_943_041L, Long.MAX_VALUE}) {
            assertThrows(MalformedMessageException.class, () -> new FarViewAnnouncement.Tile(Integer.MIN_VALUE,
                    Integer.MAX_VALUE, FarViewAnnouncement.LAYER_FULL, bytes, digest, "https://cdn.example/x.hfp"),
                    "built: " + bytes);
            assertThrows(MalformedMessageException.class,
                    () -> FarViewAnnouncement.read(MsgBuf.reading(announcementOf(bytes))), "read: " + bytes);
        }
    }
}
