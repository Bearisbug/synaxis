package io.github.bearisbug.synaxis.network;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

/**
 * The icon of the server list: a 64 by 64 PNG image, kept as the data URI the status response
 * carries ({@code docs/DESIGN-PROD.md} {@code API-PROD-024}).
 * <p>
 * Only the file header is checked, the pixels are not decoded: the PNG signature, an {@code IHDR}
 * chunk first, and 64 by 64 in it. The size is capped because a client reads at most 32,767
 * characters of status JSON and the icon is the largest part of it.
 * <p>
 * Immutable: build one once and hand it to every {@code ServerListPingEvent} that should carry it.
 */
public final class Favicon {
    /** The width and the height a client accepts. */
    public static final int SIZE = 64;
    /** The largest file accepted, which leaves more than 10,000 characters of the response to the rest. */
    public static final int MAX_BYTES = 16_384;

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    // Signature, then the IHDR chunk: its length (13), its type, then width and height
    private static final int IHDR_LENGTH_OFFSET = 8;
    private static final int IHDR_TYPE_OFFSET = 12;
    private static final int WIDTH_OFFSET = 16;
    private static final int HEIGHT_OFFSET = 20;
    private static final int IHDR_DATA_LENGTH = 13;
    // Signature, length, type, the 13 data bytes and the CRC of the chunk
    private static final int MIN_BYTES = 8 + 4 + 4 + IHDR_DATA_LENGTH + 4;
    private static final String DATA_URI_PREFIX = "data:image/png;base64,";

    private final String dataUri;

    private Favicon(String dataUri) {
        this.dataUri = dataUri;
    }

    /**
     * An icon from the bytes of a PNG file.
     *
     * @param png the file content
     * @return the icon
     * @throws IllegalArgumentException if the bytes are not a 64 by 64 PNG of at most {@link #MAX_BYTES}
     */
    public static Favicon of(byte[] png) {
        if (png.length > MAX_BYTES)
            throw new IllegalArgumentException("a server icon must be at most " + MAX_BYTES + " bytes but was " + png.length);
        if (png.length < MIN_BYTES || !Arrays.equals(png, 0, SIGNATURE.length, SIGNATURE, 0, SIGNATURE.length))
            throw new IllegalArgumentException("a server icon must be a PNG image");
        final ByteBuffer header = ByteBuffer.wrap(png);
        if (header.getInt(IHDR_LENGTH_OFFSET) != IHDR_DATA_LENGTH
                || !"IHDR".equals(new String(png, IHDR_TYPE_OFFSET, 4, StandardCharsets.US_ASCII)))
            throw new IllegalArgumentException("a server icon must be a PNG image starting with its IHDR chunk");
        final int width = header.getInt(WIDTH_OFFSET);
        final int height = header.getInt(HEIGHT_OFFSET);
        if (width != SIZE || height != SIZE)
            throw new IllegalArgumentException("a server icon must be " + SIZE + "x" + SIZE + " pixels but was "
                    + Integer.toUnsignedString(width) + "x" + Integer.toUnsignedString(height));
        return new Favicon(DATA_URI_PREFIX + Base64.getEncoder().encodeToString(png));
    }

    /**
     * The icon in a file, whose size is checked before it is read.
     *
     * @param path the PNG file
     * @return the icon
     * @throws IllegalArgumentException if the file cannot be read or is not a valid icon, naming the path
     */
    static Favicon read(Path path) {
        try {
            final long size = Files.size(path);
            if (size > MAX_BYTES)
                throw new IllegalArgumentException("a server icon must be at most " + MAX_BYTES + " bytes but was " + size);
            return of(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read " + path + ": " + e, e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage() + ": " + path, e);
        }
    }

    /**
     * The icon as the status response carries it.
     *
     * @return {@code data:image/png;base64,} followed by the file in base 64
     */
    public String dataUri() {
        return dataUri;
    }
}
