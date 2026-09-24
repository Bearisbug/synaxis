package io.github.bearisbug.synaxis.synx;

import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The uncompressed content of segment ③, little endian: block entity table {@code count u32} +
 * {@code {index u16, section_y i8, handler_len u8, handler, nbt_len u32, nbt}}, then static entity
 * table {@code count u32} + {@code {nbt_len u32, nbt}}. NBT is the uncompressed binary form with an
 * empty root name. The file stores it as one zlib stream, or nothing when both tables are empty.
 */
final class SynxEntities {
    /** Block entities in {@code (section_y, index)} order, which is the order they are written in. */
    static final Comparator<SynxBlockEntity> ORDER =
            Comparator.comparingInt(SynxBlockEntity::sectionY).thenComparingInt(SynxBlockEntity::index);

    record Tables(List<SynxBlockEntity> blockEntities, List<CompoundBinaryTag> staticEntities) {
    }

    private SynxEntities() {
    }

    static byte[] encode(List<SynxBlockEntity> blockEntities, List<CompoundBinaryTag> staticEntities) {
        if (blockEntities.isEmpty() && staticEntities.isEmpty()) return new byte[0];
        final List<SynxBlockEntity> sorted = new ArrayList<>(blockEntities);
        sorted.sort(ORDER);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeInt(output, sorted.size());
        for (SynxBlockEntity entity : sorted) {
            final byte[] handler = SynxDigests.utf8(entity.handler());
            final byte[] nbt = nbt(entity.nbt());
            output.write(entity.index() & 0xFF);
            output.write(entity.index() >>> 8);
            output.write(entity.sectionY());
            output.write(handler.length);
            output.writeBytes(handler);
            writeInt(output, nbt.length);
            output.writeBytes(nbt);
        }
        writeInt(output, staticEntities.size());
        for (CompoundBinaryTag entity : staticEntities) {
            final byte[] nbt = nbt(entity);
            writeInt(output, nbt.length);
            output.writeBytes(nbt);
        }
        return output.toByteArray();
    }

    static Tables decode(byte[] raw) {
        if (raw.length == 0) return new Tables(List.of(), List.of());
        final ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        final int blockCount = buffer.getInt();
        final List<SynxBlockEntity> blockEntities = new ArrayList<>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            final int index = Short.toUnsignedInt(buffer.getShort());
            final int sectionY = buffer.get();
            final byte[] handler = new byte[Byte.toUnsignedInt(buffer.get())];
            buffer.get(handler);
            blockEntities.add(new SynxBlockEntity(sectionY, index, new String(handler, StandardCharsets.UTF_8),
                    readNbt(buffer)));
        }
        final int staticCount = buffer.getInt();
        final List<CompoundBinaryTag> staticEntities = new ArrayList<>(staticCount);
        for (int i = 0; i < staticCount; i++) staticEntities.add(readNbt(buffer));
        if (buffer.hasRemaining()) throw new IllegalArgumentException(buffer.remaining() + " bytes after segment ③");
        return new Tables(blockEntities, staticEntities);
    }

    /** Inflates a whole zlib stream whose length is not stored anywhere. */
    static byte[] inflate(ByteBuffer input) {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(input);
            final ByteArrayOutputStream output = new ByteArrayOutputStream(input.remaining() * 4);
            final byte[] chunk = new byte[8192];
            while (!inflater.finished()) {
                final int n = inflater.inflate(chunk);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new IllegalArgumentException("zlib stream cut short");
                }
                output.write(chunk, 0, n);
            }
            if (inflater.getRemaining() != 0) throw new IllegalArgumentException("bytes after the zlib stream");
            return output.toByteArray();
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("not a zlib stream", e);
        } finally {
            inflater.end();
        }
    }

    private static CompoundBinaryTag readNbt(ByteBuffer buffer) {
        final byte[] nbt = new byte[buffer.getInt()];
        buffer.get(nbt);
        try {
            return BinaryTagIO.reader().read((DataInput) new DataInputStream(new ByteArrayInputStream(nbt)));
        } catch (IOException e) {
            throw new IllegalArgumentException("unreadable NBT in segment ③", e);
        }
    }

    private static byte[] nbt(CompoundBinaryTag tag) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(output)) {
            BinaryTagIO.writer().write(tag, (DataOutput) data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return output.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write(value);
        output.write(value >>> 8);
        output.write(value >>> 16);
        output.write(value >>> 24);
    }
}
