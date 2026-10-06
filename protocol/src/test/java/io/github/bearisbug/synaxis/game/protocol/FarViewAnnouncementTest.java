package io.github.bearisbug.synaxis.game.protocol;

import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement.Tile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The far view announcement of {@code docs/DESIGN-M3.md} {@code API-M3-015}: its layout, and every
 * payload the client must take as no announcement at all. The frozen bytes are
 * {@link FarViewVectorTest}'s.
 */
class FarViewAnnouncementTest {

    private static byte[] digest(int seed) {
        final byte[] digest = new byte[32];
        for (int i = 0; i < digest.length; i++) digest[i] = (byte) (seed * 31 + i);
        return digest;
    }

    private static Tile tile(int x, int z, int layer) {
        return new Tile(x, z, layer, 1000L + x * 10L + z, digest(x * 7 + z), "http://cdn.test/m/farview/" + x + "." + z + ".hfp");
    }

    private static byte[] encode(FarViewAnnouncement announcement) {
        final MsgBuf buf = MsgBuf.writing();
        announcement.write(buf);
        return buf.toByteArray();
    }

    @Test
    void anAnnouncementRoundTrips() {
        final FarViewAnnouncement announcement = new FarViewAnnouncement("map-0123456789ab-p776", 2, 4, -64, 256,
                List.of(tile(-1, -1, 1), tile(-1, 0, 1), tile(0, -1, 1), tile(0, 0, 0), tile(0, 1, 1), tile(1, 0, 1)));
        final byte[] bytes = encode(announcement);
        final MsgBuf reader = MsgBuf.reading(bytes);
        assertEquals(announcement, FarViewAnnouncement.read(reader));
        assertEquals(0, reader.remaining());
        assertEquals(FarViewAnnouncement.FORMAT_VERSION, bytes[0], "format_version leads");
        assertEquals("synaxis:farview", FarViewAnnouncement.CHANNEL);
    }

    @Test
    void aTileComparesItsDigestByContent() {
        final Tile one = tile(0, 0, 0);
        final Tile same = new Tile(0, 0, 0, one.bytes(), one.sha256(), one.url());
        assertEquals(one, same);
        assertEquals(one.hashCode(), same.hashCode());
        assertNotEquals(one, new Tile(0, 0, 0, one.bytes(), digest(99), one.url()));
        final byte[] mine = one.sha256();
        mine[0] ^= 1;
        assertArrayEquals(digest(0), one.sha256(), "the digest is copied out");
        assertTrue(one.toString().contains("sha256="), one.toString());
    }

    @Test
    void theTilesMustRiseStrictlyByXThenZ() {
        assertThrows(MalformedMessageException.class, () -> new FarViewAnnouncement("b", 2, 4, 0, 256,
                List.of(tile(0, 1, 0), tile(0, 0, 1))));
        assertThrows(MalformedMessageException.class, () -> new FarViewAnnouncement("b", 2, 4, 0, 256,
                List.of(tile(1, -5, 0), tile(0, 5, 1))));
        assertThrows(MalformedMessageException.class, () -> new FarViewAnnouncement("b", 2, 4, 0, 256,
                List.of(tile(0, 0, 0), tile(0, 0, 1))), "one tile twice");
    }

    @Test
    void theFieldsStayInsideTheirCaps() {
        assertThrows(MalformedMessageException.class, () -> new FarViewAnnouncement("b", 2, 4, 0, 256, List.of()));
        assertThrows(MalformedMessageException.class, () -> new FarViewAnnouncement("b", 2, 4, 0, 256,
                List.of(tile(0, 0, 1))), "no tile is announced whole");
        assertThrows(MalformedMessageException.class, () -> tile(0, 0, 2));
        assertThrows(MalformedMessageException.class, () -> new Tile(0, 0, 0, 0, digest(0), "u"), "an empty object");
        assertThrows(MalformedMessageException.class, () -> new Tile(0, 0, 0, 1, new byte[31], "u"));
        assertThrows(MalformedMessageException.class, () -> new Tile(0, 0, 0, 1, digest(0), "u".repeat(1025)));
        new Tile(0, 0, 0, 1, digest(0), "u".repeat(1024));
        assertThrows(MalformedMessageException.class,
                () -> new FarViewAnnouncement("b".repeat(97), 2, 4, 0, 256, List.of(tile(0, 0, 0))));
        new FarViewAnnouncement("b".repeat(96), 2, 4, 0, 256, List.of(tile(0, 0, 0)));
        assertThrows(MalformedMessageException.class, () -> new FarViewAnnouncement("b", 3, 4, 0, 256, List.of(tile(0, 0, 0))));
        assertThrows(MalformedMessageException.class, () -> new FarViewAnnouncement("b", 2, 3, 0, 256, List.of(tile(0, 0, 0))));
        final List<Tile> many = new ArrayList<>();
        for (int i = 0; i < 65; i++) many.add(tile(i, 0, i == 0 ? 0 : 1));
        assertThrows(MalformedMessageException.class, () -> new FarViewAnnouncement("b", 2, 4, 0, 256, many));
        new FarViewAnnouncement("b", 2, 4, 0, 256, many.subList(0, 64));
    }

    /** Builds a payload field by field, so that it can say what the record refuses to. */
    private static byte[] raw(int formatVersion, int count, Consumer<MsgBuf> tiles) {
        final MsgBuf buf = MsgBuf.writing();
        buf.writeVarInt(formatVersion);
        buf.writeString("b");
        buf.writeUnsignedByte(2);
        buf.writeUnsignedByte(4);
        buf.writeVarInt(-64);
        buf.writeVarInt(256);
        buf.writeVarInt(count);
        tiles.accept(buf);
        return buf.toByteArray();
    }

    private static void rawTile(MsgBuf buf, int x, int z, int layer, String url) {
        rawTile(buf, x, z, layer, 1000, url);
    }

    private static void rawTile(MsgBuf buf, int x, int z, int layer, long bytes, String url) {
        buf.writeVarInt(x);
        buf.writeVarInt(z);
        buf.writeUnsignedByte(layer);
        buf.writeVarLong(bytes);
        buf.writeBytes(digest(1));
        buf.writeString(url);
    }

    private static void assertMalformed(byte[] payload) {
        assertThrows(MalformedMessageException.class, () -> FarViewAnnouncement.read(MsgBuf.reading(payload)));
    }

    @Test
    void theDecoderRefusesWhatTheClientTakesAsNoAnnouncement() {
        final byte[] good = raw(1, 1, buf -> rawTile(buf, 0, 0, 0, "u"));
        FarViewAnnouncement.read(MsgBuf.reading(good));
        assertMalformed(raw(2, 1, buf -> rawTile(buf, 0, 0, 0, "u")));
        assertMalformed(raw(1, 0, _ -> {
        }));
        assertMalformed(raw(1, 65, buf -> {
            for (int i = 0; i < 65; i++) rawTile(buf, i, 0, i == 0 ? 0 : 1, "u");
        }));
        assertMalformed(raw(1, 2, buf -> {
            rawTile(buf, 0, 0, 0, "u");
            rawTile(buf, 0, 0, 1, "u");
        }));
        assertMalformed(raw(1, 2, buf -> {
            rawTile(buf, 0, 0, 0, "u");
            rawTile(buf, 1, 0, 2, "u");
        }));
        assertMalformed(raw(1, 2, buf -> {
            rawTile(buf, 0, 0, 1, "u");
            rawTile(buf, 1, 0, 1, "u");
        }));
        assertMalformed(raw(1, 1, buf -> rawTile(buf, 0, 0, 0, "u".repeat(1025))));
        assertMalformed(raw(1, 5, buf -> rawTile(buf, 0, 0, 0, "u")));
        assertMalformed(Arrays.copyOf(good, good.length - 1));
    }

    /** An object is at most 40 MiB where a tile is built and where it is read. */
    @Test
    void anObjectIsAtMost40MiB() {
        final Tile atCap = new Tile(0, 0, 0, 41_943_040L, digest(0), "u");
        final FarViewAnnouncement announcement = new FarViewAnnouncement("b", 2, 4, 0, 256, List.of(atCap));
        assertEquals(announcement, FarViewAnnouncement.read(MsgBuf.reading(encode(announcement))));
        assertEquals(41_943_040L, FarViewAnnouncement.read(MsgBuf.reading(raw(1, 1,
                buf -> rawTile(buf, 0, 0, 0, 41_943_040L, "u")))).tiles().getFirst().bytes());
        assertThrows(MalformedMessageException.class, () -> new Tile(0, 0, 0, 41_943_041L, digest(0), "u"), "built");
        final byte[] over = raw(1, 1, buf -> rawTile(buf, 0, 0, 0, 41_943_041L, "u"));
        final MalformedMessageException thrown =
                assertThrows(MalformedMessageException.class, () -> FarViewAnnouncement.read(MsgBuf.reading(over)), "read");
        assertEquals("tile 0,0: bytes 41943041 is over the cap of 41943040", thrown.getMessage());
    }

    @Test
    void theCountIsCheckedBeforeAnythingIsAllocated() {
        // 64 tiles declared, three bytes follow: refused at the count
        final byte[] payload = raw(1, 64, buf -> buf.writeBytes(new byte[3]));
        final MalformedMessageException thrown =
                assertThrows(MalformedMessageException.class, () -> FarViewAnnouncement.read(MsgBuf.reading(payload)));
        assertTrue(thrown.getMessage().contains("count 64"), thrown.getMessage());
    }
}
