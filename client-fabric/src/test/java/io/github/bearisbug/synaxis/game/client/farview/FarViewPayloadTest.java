package io.github.bearisbug.synaxis.game.client.farview;

import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code synaxis:farview} as the client's configuration payload ({@code docs/DESIGN-M3.md}
 * {@code API-M3-015} 宣告布局): the frozen announcements decode and re-encode through Minecraft's own
 * buffer, and whatever is not exactly one announcement is no announcement rather than an exception
 * that would end the connection.
 */
class FarViewPayloadTest {

    private static byte[] vector(String name) throws IOException {
        return Files.readAllBytes(Path.of(System.getProperty("synaxis.farview-vectors.dir")).resolve(name));
    }

    private static FarViewPayload viaBuffer(byte[] bytes) {
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBytes(bytes);
        return FarViewPayload.CODEC.decode(buf);
    }

    @Test
    void theChannelIsSynaxisFarview() {
        assertEquals("synaxis:farview", FarViewPayload.TYPE.id().toString());
        assertEquals(FarViewAnnouncement.CHANNEL, FarViewPayload.TYPE.id().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"announce-one-tile.bin", "announce-four-tiles.bin", "announce-nine-tiles.bin"})
    void anAnnouncementDecodesAndReEncodesByteForByte(String file) throws IOException {
        final byte[] frozen = vector(file);
        final FarViewPayload payload = viaBuffer(frozen);
        assertNotNull(payload.announcement(), payload.problem());
        assertEquals(FarViewAnnouncement.read(MsgBuf.reading(frozen)), payload.announcement());
        final FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
        FarViewPayload.CODEC.encode(out, payload);
        final byte[] bytes = new byte[out.readableBytes()];
        out.readBytes(bytes);
        assertArrayEquals(frozen, bytes, file);
    }

    @ParameterizedTest
    @ValueSource(strings = {"edge/announce-count-65.bin", "edge/announce-url-too-long.bin", "edge/announce-duplicate-tile.bin",
            "edge/announce-layer-2.bin", "edge/announce-no-full-tile.bin"})
    void anEdgeAnnouncementIsNoAnnouncement(String file) throws IOException {
        final FarViewPayload payload = viaBuffer(vector(file));
        assertNull(payload.announcement(), file);
        assertFalse(payload.problem().isEmpty(), file);
    }

    @Test
    void anUnknownFormatVersionIsNoAnnouncement() throws IOException {
        final byte[] bytes = vector("announce-one-tile.bin");
        bytes[0] = 2;
        final FarViewPayload payload = viaBuffer(bytes);
        assertNull(payload.announcement());
        assertEquals("format_version 2 is not 1", payload.problem());
    }

    @Test
    void bytesAfterTheAnnouncementMakeItNone() throws IOException {
        final byte[] frozen = vector("announce-one-tile.bin");
        assertNull(viaBuffer(Arrays.copyOf(frozen, frozen.length + 1)).announcement());
    }

    @Test
    void aTruncatedOrEmptyPayloadIsNoAnnouncement() throws IOException {
        final byte[] frozen = vector("announce-nine-tiles.bin");
        for (int length : List.of(0, 1, 10, frozen.length - 1)) {
            assertNull(viaBuffer(Arrays.copyOf(frozen, length)).announcement(), "the first " + length + " bytes");
        }
    }
}
