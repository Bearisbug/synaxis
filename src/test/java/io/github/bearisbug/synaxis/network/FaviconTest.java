package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server icon rules of {@code docs/DESIGN-PROD.md} {@code API-PROD-024}: a 64 by 64 PNG of at
 * most 16,384 bytes, and a process whose configured icon breaks them is never created.
 */
public class FaviconTest {

    @TempDir
    Path directory;

    @Test
    public void aSixtyFourPixelSquarePngBecomesADataUri() {
        final byte[] png = Pngs.solid(64, 64);
        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(png), Favicon.of(png).dataUri());
    }

    @Test
    public void anyOtherSizeIsRefused() {
        assertEquals("a server icon must be 64x64 pixels but was 16x16", refusal(Pngs.solid(16, 16)));
        assertEquals("a server icon must be 64x64 pixels but was 64x32", refusal(Pngs.solid(64, 32)));
        assertEquals("a server icon must be 64x64 pixels but was 128x64", refusal(Pngs.solid(128, 64)));
    }

    /** The cap is on the file, so it is exact: 16,384 bytes pass, one more does not. */
    @Test
    public void theFileMayBeSixteenKibibytesAndNotOneByteMore() {
        final byte[] atTheCap = Pngs.paddedTo(Favicon.MAX_BYTES);
        assertEquals(Favicon.MAX_BYTES, atTheCap.length);
        assertTrue(Favicon.of(atTheCap).dataUri().startsWith("data:image/png;base64,"));
        assertEquals("a server icon must be at most 16384 bytes but was 16385",
                refusal(Pngs.paddedTo(Favicon.MAX_BYTES + 1)));
    }

    @Test
    public void whatIsNotAPngIsRefused() {
        assertEquals("a server icon must be a PNG image",
                refusal("GIF89a, sixty four by sixty four".getBytes(StandardCharsets.US_ASCII)));
        final byte[] png = Pngs.solid(64, 64);
        assertEquals("a server icon must be a PNG image", refusal(Arrays.copyOf(png, 32)));
        // The same bytes with the header chunk renamed: the width and height are not where they seem
        final byte[] renamed = png.clone();
        renamed[12] = 'i';
        assertEquals("a server icon must be a PNG image starting with its IHDR chunk", refusal(renamed));
    }

    @Test
    public void readingAFileNamesItInTheRefusal() throws IOException {
        final Path small = Files.write(directory.resolve("small.png"), Pngs.solid(16, 16));
        assertEquals("a server icon must be 64x64 pixels but was 16x16: " + small,
                assertThrows(IllegalArgumentException.class, () -> Favicon.read(small)).getMessage());

        // Refused from its size alone, before it is read
        final Path large = Files.write(directory.resolve("large.png"), new byte[20_000]);
        assertEquals("a server icon must be at most 16384 bytes but was 20000: " + large,
                assertThrows(IllegalArgumentException.class, () -> Favicon.read(large)).getMessage());

        final Path missing = directory.resolve("missing.png");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Favicon.read(missing)).getMessage()
                .startsWith("cannot read " + missing + ": java.nio.file.NoSuchFileException"));
    }

    /** {@code server-app} turns this exception into {@code Refusing to start}, the port never opens. */
    @Test
    public void aProcessWithAnInvalidIconIsNotCreated() throws IOException {
        final Path small = Files.write(directory.resolve("small.png"), Pngs.solid(16, 16));
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ServerProcess.of(ServerConfig.builder().serverIcon(small.toString()).build()));
        assertEquals("synaxis.server-icon is not a valid icon: a server icon must be 64x64 pixels but was 16x16: "
                + small, refused.getMessage());

        final byte[] png = Pngs.solid(64, 64);
        final Path icon = Files.write(directory.resolve("icon.png"), png);
        final ServerProcess withIcon = ServerProcess.of(ServerConfig.builder().serverIcon(icon.toString()).build());
        final ServerProcess without = ServerProcess.of(ServerConfig.defaults());
        try {
            final Favicon loaded = withIcon.connection().serverIcon();
            assertEquals(Favicon.of(png).dataUri(), loaded == null ? null : loaded.dataUri());
            assertNull(without.connection().serverIcon(), "no icon unless one is configured");
        } finally {
            withIcon.stop();
            without.stop();
        }
    }

    private static String refusal(byte[] png) {
        return assertThrows(IllegalArgumentException.class, () -> Favicon.of(png)).getMessage();
    }
}
