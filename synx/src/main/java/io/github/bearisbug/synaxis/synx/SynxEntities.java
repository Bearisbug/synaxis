package io.github.bearisbug.synaxis.synx;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagType;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The uncompressed content of segment ③, little endian: block entity table {@code count u32} +
 * {@code {index u16, section_y i8, handler_len u8, handler, nbt_len u32, nbt}}, then static entity
 * table {@code count u32} + {@code {nbt_len u32, nbt}}. NBT is the uncompressed binary form with an
 * empty root name and the keys of each compound sorted; static entities keep the order the baker
 * gives them in. The file stores it as one zlib stream, or nothing when both tables are empty.
 */
final class SynxEntities {
    /**
     * The most segment ③ inflates to, 16 MiB, and the accounting cap each of its NBT is read with (M3-S,
     * {@code API-M3-001} 长度上限): adventure-nbt counts before it allocates, so a stated length never
     * allocates more than this. Its default cap, 131,082, refuses a chest full of written books.
     */
    static final int MAX_BYTES = 16 << 20;
    /** Block entities in {@code (section_y, index)} order, which is the order they are written in. */
    static final Comparator<SynxBlockEntity> ORDER =
            Comparator.comparingInt(SynxBlockEntity::sectionY).thenComparingInt(SynxBlockEntity::index);

    record Tables(List<SynxBlockEntity> blockEntities, List<CompoundBinaryTag> staticEntities) {
    }

    private SynxEntities() {
    }

    /**
     * Segment ③ uncompressed, read back once with the reader of the shard, so that what is written is
     * what a shard loads (M3-S).
     *
     * @throws IllegalArgumentException if it is over {@value #MAX_BYTES} bytes or does not read back
     */
    static byte[] encode(List<SynxBlockEntity> blockEntities, List<CompoundBinaryTag> staticEntities) {
        if (blockEntities.isEmpty() && staticEntities.isEmpty()) return new byte[0];
        final byte[] encoded = write(blockEntities, staticEntities);
        if (encoded.length > MAX_BYTES) {
            throw new IllegalArgumentException("segment ③ is " + encoded.length + " bytes, over " + MAX_BYTES);
        }
        try {
            decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("segment ③ does not read back: " + e.getMessage(), e);
        }
        return encoded;
    }

    private static byte[] write(List<SynxBlockEntity> blockEntities, List<CompoundBinaryTag> staticEntities) {
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

    /**
     * @throws IllegalArgumentException if {@code raw} is not a well formed segment ③, whatever
     *                                  is wrong with it
     */
    static Tables decode(byte[] raw) {
        if (raw.length == 0) return new Tables(List.of(), List.of());
        try {
            return decode(ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN));
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("segment ③ cut short", e);
        }
    }

    private static Tables decode(ByteBuffer buffer) {
        final int blockCount = count(buffer);
        final List<SynxBlockEntity> blockEntities = new ArrayList<>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            final int index = Short.toUnsignedInt(buffer.getShort());
            final int sectionY = buffer.get();
            final byte[] handler = new byte[Byte.toUnsignedInt(buffer.get())];
            buffer.get(handler);
            blockEntities.add(new SynxBlockEntity(sectionY, index, new String(handler, StandardCharsets.UTF_8),
                    readNbt(buffer)));
        }
        final int staticCount = count(buffer);
        final List<CompoundBinaryTag> staticEntities = new ArrayList<>(staticCount);
        for (int i = 0; i < staticCount; i++) staticEntities.add(readNbt(buffer));
        if (buffer.hasRemaining()) throw new IllegalArgumentException(buffer.remaining() + " bytes after segment ③");
        return new Tables(blockEntities, staticEntities);
    }

    /**
     * Inflates a whole zlib stream whose length is not stored anywhere, to {@value #MAX_BYTES} bytes at most.
     *
     * @throws IllegalArgumentException if it is not one zlib stream, or inflates to more
     */
    static byte[] inflate(ByteBuffer input) {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(input);
            final ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(MAX_BYTES, input.remaining() * 4L));
            final byte[] chunk = new byte[8192];
            while (!inflater.finished()) {
                final int n = inflater.inflate(chunk);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new IllegalArgumentException("zlib stream cut short");
                }
                if (output.size() + n > MAX_BYTES) {
                    throw new IllegalArgumentException("segment ③ inflates to more than " + MAX_BYTES + " bytes");
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

    /** A table size or NBT length, which can never exceed the bytes left. */
    private static int count(ByteBuffer buffer) {
        final int count = buffer.getInt();
        if (count < 0 || count > buffer.remaining()) {
            throw new IllegalArgumentException("length " + Integer.toUnsignedString(count) + " with "
                    + buffer.remaining() + " bytes left in segment ③");
        }
        return count;
    }

    private static CompoundBinaryTag readNbt(ByteBuffer buffer) {
        final byte[] nbt = new byte[count(buffer)];
        buffer.get(nbt);
        try {
            return BinaryTagIO.reader(MAX_BYTES).read((DataInput) new DataInputStream(new ByteArrayInputStream(nbt)));
        } catch (IOException e) {
            throw new IllegalArgumentException("unreadable NBT in segment ③", e);
        }
    }

    /**
     * The binary NBT of {@code tag} with an empty root name and the keys of every compound in
     * {@link String#compareTo} order. Adventure writes a compound in the order of the hash map behind
     * it, which depends on how the compound was built; equal content has to give equal bytes.
     */
    private static byte[] nbt(CompoundBinaryTag tag) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(output)) {
            data.writeByte(BinaryTagTypes.COMPOUND.id());
            data.writeUTF("");
            writeCompound(tag, data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return output.toByteArray();
    }

    private static void writeCompound(CompoundBinaryTag tag, DataOutput output) throws IOException {
        final List<String> keys = new ArrayList<>(tag.keySet());
        keys.sort(Comparator.naturalOrder());
        for (String key : keys) {
            final BinaryTag value = Objects.requireNonNull(tag.get(key));
            output.writeByte(value.type().id());
            output.writeUTF(key);
            writePayload(value, output);
        }
        output.writeByte(BinaryTagTypes.END.id());
    }

    private static void writePayload(BinaryTag tag, DataOutput output) throws IOException {
        switch (tag) {
            case CompoundBinaryTag compound -> writeCompound(compound, output);
            case ListBinaryTag list -> {
                final ListBinaryTag wrapped = list.wrapHeterogeneity();
                output.writeByte(wrapped.elementType().id());
                output.writeInt(wrapped.size());
                for (BinaryTag element : wrapped) writePayload(element, output);
            }
            default -> writeValue(tag.type(), tag, output);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends BinaryTag> void writeValue(BinaryTagType<T> type, BinaryTag tag, DataOutput output)
            throws IOException {
        type.write((T) tag, output);
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write(value);
        output.write(value >>> 8);
        output.write(value >>> 16);
        output.write(value >>> 24);
    }
}
