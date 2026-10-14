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
 * empty root name, the keys of each compound sorted, and a list whose elements differ in type written
 * wrapped, each element in a compound under the empty key; static entities keep the order the baker
 * gives them in. The file stores it as one zlib stream, or nothing when both tables are empty.
 */
final class SynxEntities {
    /**
     * The most segment ③ inflates to, 16 MiB, and the accounting cap each of its NBT is read with (M3-S,
     * {@code API-M3-001} 长度上限). Its default cap, 131,082, refuses a chest full of written books.
     * Adventure-nbt checks a stated length against the cap before it allocates, but only compares: each
     * list of a nest of lists passes on its own and allocates what it states, so each NBT is walked first
     * ({@link #checkShape}).
     */
    static final int MAX_BYTES = 16 << 20;
    /** How deep NBT nests, as adventure-nbt reads it. */
    private static final int MAX_DEPTH = 512;

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
        sorted.sort(SynxBlockEntity.ORDER);
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
        // Not sized by the counts: a count only has to fit the bytes left, an entry takes 4 bytes or more
        final int blockCount = count(buffer);
        final List<SynxBlockEntity> blockEntities = new ArrayList<>();
        for (int i = 0; i < blockCount; i++) {
            final int index = Short.toUnsignedInt(buffer.getShort());
            final int sectionY = buffer.get();
            final byte[] handler = new byte[Byte.toUnsignedInt(buffer.get())];
            buffer.get(handler);
            final CompoundBinaryTag nbt;
            try {
                nbt = readNbt(buffer);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(e.getMessage() + " (block entity at x " + (index & 15) + ", y "
                        + (sectionY * 16 + (index >> 8)) + ", z " + ((index >> 4) & 15) + " of the chunk)", e);
            }
            blockEntities.add(new SynxBlockEntity(sectionY, index, new String(handler, StandardCharsets.UTF_8), nbt));
        }
        final int staticCount = count(buffer);
        final List<CompoundBinaryTag> staticEntities = new ArrayList<>();
        for (int i = 0; i < staticCount; i++) {
            try {
                staticEntities.add(readNbt(buffer));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(e.getMessage() + " (static entity " + (i + 1) + " of " + staticCount + ")", e);
            }
        }
        if (buffer.hasRemaining()) throw new IllegalArgumentException(buffer.remaining() + " bytes after segment ③");
        return new Tables(blockEntities, staticEntities);
    }

    /**
     * Inflates segment ③, one zlib stream that fills {@code input}. The record does not store how long it
     * inflates, so it stops at {@value #MAX_BYTES} bytes.
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
            checkShape(nbt);
            return BinaryTagIO.reader(MAX_BYTES).read((DataInput) new DataInputStream(new ByteArrayInputStream(nbt)));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("unreadable NBT in segment ③: " + e.getMessage(), e);
        }
    }

    /**
     * Walks {@code nbt} without allocating: every list and array has the elements it states, a list of end
     * tags is empty, and it nests {@value #MAX_DEPTH} deep at most. Adventure-nbt then allocates in
     * proportion to the bytes, as each element it reads takes one byte or more.
     *
     * @throws IllegalArgumentException if it does not
     */
    private static void checkShape(byte[] nbt) {
        final ByteBuffer shape = ByteBuffer.wrap(nbt);
        try {
            if (shape.get() != BinaryTagTypes.COMPOUND.id()) throw new IllegalArgumentException("the root is not a compound");
            skip(shape, Short.toUnsignedInt(shape.getShort()));
            walk(shape, BinaryTagTypes.COMPOUND.id(), 1);
        } catch (BufferUnderflowException _) {
            throw new IllegalArgumentException("cut short");
        }
    }

    /**
     * Moves {@code nbt} past one payload of {@code type}, which nests {@code depth} deep. The tag ids of the
     * binary form: 1 byte, 2 short, 3 int, 4 long, 5 float, 6 double, 7 byte array, 8 string, 9 list,
     * 10 compound, 11 int array, 12 long array.
     */
    private static void walk(ByteBuffer nbt, byte type, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("nested deeper than " + MAX_DEPTH);
        switch (type) {
            case 1 -> skip(nbt, 1);
            case 2 -> skip(nbt, 2);
            case 3, 5 -> skip(nbt, 4);
            case 4, 6 -> skip(nbt, 8);
            case 7 -> skip(nbt, stated(nbt, 1));
            case 8 -> skip(nbt, Short.toUnsignedInt(nbt.getShort()));
            case 9 -> {
                final byte element = nbt.get();
                final int size = stated(nbt, 1);
                if (element == 0 && size != 0) throw new IllegalArgumentException("a list of " + size + " end tags");
                for (int i = 0; i < size; i++) walk(nbt, element, depth + 1);
            }
            case 10 -> {
                for (byte entry = nbt.get(); entry != 0; entry = nbt.get()) {
                    skip(nbt, Short.toUnsignedInt(nbt.getShort()));
                    walk(nbt, entry, depth + 1);
                }
            }
            case 11 -> skip(nbt, stated(nbt, 4) * 4);
            case 12 -> skip(nbt, stated(nbt, 8) * 8);
            default -> throw new IllegalArgumentException("tag type " + type);
        }
    }

    /** A stated number of elements of {@code width} bytes or more each, which can never exceed the bytes left. */
    private static int stated(ByteBuffer nbt, int width) {
        final int size = nbt.getInt();
        if (size < 0 || (long) size * width > nbt.remaining()) {
            throw new IllegalArgumentException("length " + size + " with " + nbt.remaining() + " bytes left");
        }
        return size;
    }

    private static void skip(ByteBuffer nbt, int bytes) {
        if (bytes > nbt.remaining()) throw new BufferUnderflowException();
        nbt.position(nbt.position() + bytes);
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
                // The binary form has one element type per list: a mixed list goes out wrapped in compounds
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
