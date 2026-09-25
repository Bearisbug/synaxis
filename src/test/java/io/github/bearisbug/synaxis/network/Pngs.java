package io.github.bearisbug.synaxis.network;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Builds real PNG files, one colour all over, for the server icon tests: a decoder reads them.
 */
final class Pngs {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** An 8 bit RGBA image of one colour. */
    static byte[] solid(int width, int height) {
        return solid(width, height, 0);
    }

    /**
     * The same with a {@code tEXt} chunk of {@code textBytes} bytes after the header, which makes
     * the file that much larger and changes nothing a client shows.
     */
    static byte[] solid(int width, int height, int textBytes) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(SIGNATURE);
        chunk(out, "IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0).array());
        if (textBytes > 0) {
            final byte[] text = new byte[textBytes];
            final byte[] keyword = "Comment\0".getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(keyword, 0, text, 0, keyword.length);
            for (int i = keyword.length; i < text.length; i++) text[i] = 'x';
            chunk(out, "tEXt", text);
        }
        final int stride = 1 + width * 4;
        final byte[] pixels = new byte[height * stride];
        for (int row = 0; row < height; row++) {
            for (int x = 0; x < width; x++) {
                final int at = row * stride + 1 + x * 4;
                pixels[at] = 0x30;
                pixels[at + 1] = (byte) 0x80;
                pixels[at + 2] = (byte) 0xC0;
                pixels[at + 3] = (byte) 0xFF;
            }
        }
        chunk(out, "IDAT", deflate(pixels));
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** The bytes of a file this big whose {@code tEXt} chunk pads a 64 by 64 image out to it. */
    static byte[] paddedTo(int size) {
        final int unpadded = solid(Favicon.SIZE, Favicon.SIZE).length;
        // A tEXt chunk adds its data plus 12 bytes of length, type and CRC
        return solid(Favicon.SIZE, Favicon.SIZE, size - unpadded - 12);
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        final byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        final CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static byte[] deflate(byte[] data) {
        final Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        return out.toByteArray();
    }

    private Pngs() {
    }
}
