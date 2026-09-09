package net.minestom.server.network;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;

import java.io.DataInputStream;
import java.io.UTFDataFormatException;
import java.nio.charset.StandardCharsets;

import static net.minestom.server.network.NetworkBuffer.BYTE;
import static net.minestom.server.network.NetworkBuffer.BYTE_ARRAY;
import static net.minestom.server.network.NetworkBuffer.FixedRawBytes;
import static net.minestom.server.network.NetworkBuffer.RAW_BYTES;
import static net.minestom.server.network.NetworkBuffer.SHORT;
import static net.minestom.server.network.NetworkBuffer.UNSIGNED_SHORT;

final class NetworkBufferStringTypes {

    record StringType() implements NetworkBufferTypeImpl<String> {
        @Override
        public void write(NetworkBuffer buffer, String value) {
            final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            buffer.write(BYTE_ARRAY, bytes);
        }

        @Override
        public String read(NetworkBuffer buffer) {
            final byte[] bytes = buffer.read(BYTE_ARRAY);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    record StringTerminatedType() implements NetworkBufferTypeImpl<String> {
        @Override
        public void write(NetworkBuffer buffer, String value) {
            final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            byte[] terminated = new byte[bytes.length + 1];
            System.arraycopy(bytes, 0, terminated, 0, bytes.length);
            terminated[terminated.length - 1] = 0;
            buffer.write(RAW_BYTES, terminated);
        }

        @Override
        public String read(NetworkBuffer buffer) {
            ByteArrayList bytes = new ByteArrayList();
            byte b;
            while ((b = buffer.read(BYTE)) != 0) {
                bytes.add(b);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * This is a very gross version of {@link java.io.DataOutputStream#writeUTF(String)} & ${@link DataInputStream#readUTF()}. We need the data in the java
     * modified utf-8 format for Component, and I couldnt find a method without creating a new buffer for it.
     */
    record IOUTF8StringType() implements NetworkBufferTypeImpl<String> {
        @Override
        public void write(NetworkBuffer buffer, String value) {
            final int strlen = value.length();
            int utflen = strlen; // optimized for ASCII

            for (int i = 0; i < strlen; i++) {
                int c = value.charAt(i);
                if (c >= 0x80 || c == 0)
                    utflen += (c >= 0x800) ? 2 : 1;
            }

            if (utflen > 65535 || /* overflow */ utflen < strlen)
                throw new RuntimeException("UTF-8 string too long");

            buffer.write(SHORT, (short) utflen);
            buffer.ensureWritable(utflen);
            var impl = (NetworkBufferImpl) buffer;
            int i;
            for (i = 0; i < strlen; i++) { // optimized for initial run of ASCII
                int c = value.charAt(i);
                if (c >= 0x80 || c == 0) break;
                impl._putByte(buffer.writeIndex(), (byte) c);
                impl.advanceWrite(1);
            }

            for (; i < strlen; i++) {
                int c = value.charAt(i);
                if (c < 0x80 && c != 0) {
                    impl._putByte(buffer.writeIndex(), (byte) c);
                    impl.advanceWrite(1);
                } else if (c >= 0x800) {
                    impl._putByte(buffer.writeIndex(), (byte) (0xE0 | ((c >> 12) & 0x0F)));
                    impl._putByte(buffer.writeIndex() + 1, (byte) (0x80 | ((c >> 6) & 0x3F)));
                    impl._putByte(buffer.writeIndex() + 2, (byte) (0x80 | ((c >> 0) & 0x3F)));
                    impl.advanceWrite(3);
                } else {
                    impl._putByte(buffer.writeIndex(), (byte) (0xC0 | ((c >> 6) & 0x1F)));
                    impl._putByte(buffer.writeIndex() + 1, (byte) (0x80 | ((c >> 0) & 0x3F)));
                    impl.advanceWrite(2);
                }
            }
        }

        @Override
        public String read(NetworkBuffer buffer) {
            int utflen = buffer.read(UNSIGNED_SHORT);
            if (buffer.readableBytes() < utflen) throw new IllegalArgumentException("Invalid String size.");
            byte[] bytearr = buffer.read(FixedRawBytes(utflen));
            final char[] chararr = new char[utflen];

            int c, char2, char3;
            int count = 0;
            int chararr_count = 0;

            while (count < utflen) {
                c = (int) bytearr[count] & 0xff;
                if (c > 127) break;
                count++;
                chararr[chararr_count++] = (char) c;
            }

            while (count < utflen) {
                c = (int) bytearr[count] & 0xff;
                try { // Surround in try catch to throw a runtime exception instead of a checked one
                    switch (c >> 4) {
                        case 0, 1, 2, 3, 4, 5, 6, 7 -> {
                            /* 0xxxxxxx*/
                            count++;
                            chararr[chararr_count++] = (char) c;
                        }
                        case 12, 13 -> {
                            /* 110x xxxx   10xx xxxx*/
                            count += 2;
                            if (count > utflen)
                                throw new UTFDataFormatException(
                                        "malformed input: partial character at end");
                            char2 = bytearr[count - 1];
                            if ((char2 & 0xC0) != 0x80)
                                throw new UTFDataFormatException(
                                        "malformed input around byte " + count);
                            chararr[chararr_count++] = (char) (((c & 0x1F) << 6) |
                                    (char2 & 0x3F));
                        }
                        case 14 -> {
                            /* 1110 xxxx  10xx xxxx  10xx xxxx */
                            count += 3;
                            if (count > utflen)
                                throw new UTFDataFormatException(
                                        "malformed input: partial character at end");
                            char2 = bytearr[count - 2];
                            char3 = bytearr[count - 1];
                            if (((char2 & 0xC0) != 0x80) || ((char3 & 0xC0) != 0x80))
                                throw new UTFDataFormatException(
                                        "malformed input around byte " + (count - 1));
                            chararr[chararr_count++] = (char) (((c & 0x0F) << 12) |
                                    ((char2 & 0x3F) << 6) |
                                    ((char3 & 0x3F) << 0));
                        }
                        default ->
                            /* 10xx xxxx,  1111 xxxx */
                                throw new UTFDataFormatException(
                                        "malformed input around byte " + count);
                    }
                } catch (UTFDataFormatException e) {
                    throw new IllegalArgumentException(e);
                }
            }
            // The number of chars produced may be less than utflen
            return new String(chararr, 0, chararr_count);
        }
    }
}
