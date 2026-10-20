package io.github.bearisbug.synaxis.network;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.EntityPose;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.coordinate.Direction;
import io.github.bearisbug.synaxis.utils.Either;
import io.github.bearisbug.synaxis.utils.Unit;
import io.github.bearisbug.synaxis.crypto.KeyUtils;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import javax.crypto.Cipher;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.security.PublicKey;
import java.time.Instant;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.zip.DataFormatException;

public sealed interface NetworkBuffer permits NetworkBufferImpl {
    Type<Unit> UNIT = new NetworkBufferPrimitiveTypes.UnitType();
    Type<Boolean> BOOLEAN = new NetworkBufferPrimitiveTypes.BooleanType();
    Type<Byte> BYTE = new NetworkBufferPrimitiveTypes.ByteType();
    Type<Short> UNSIGNED_BYTE = new NetworkBufferPrimitiveTypes.UnsignedByteType();
    Type<Short> SHORT = new NetworkBufferPrimitiveTypes.ShortType();
    Type<Integer> UNSIGNED_SHORT = new NetworkBufferPrimitiveTypes.UnsignedShortType();
    Type<Integer> INT = new NetworkBufferPrimitiveTypes.IntType();
    Type<Long> UNSIGNED_INT = new NetworkBufferPrimitiveTypes.UnsignedIntType();
    Type<Long> LONG = new NetworkBufferPrimitiveTypes.LongType();
    Type<Float> FLOAT = new NetworkBufferPrimitiveTypes.FloatType();
    Type<Double> DOUBLE = new NetworkBufferPrimitiveTypes.DoubleType();
    Type<Integer> VAR_INT = new NetworkBufferVarNumberTypes.VarIntType();
    /**
     * Vanilla OptionalVarInt: writes {@code value + 1} as a VarInt, 0 meaning absent. Unlike
     * {@code VAR_INT.optional()} there is no boolean prefix. -1 encodes the same as null and reads
     * back as null.
     */
    Type<@Nullable Integer> OPTIONAL_VAR_INT = new NetworkBufferVarNumberTypes.OptionalVarIntType();
    Type<Integer> VAR_INT_3 = new NetworkBufferVarNumberTypes.VarInt3Type();
    Type<Long> VAR_LONG = new NetworkBufferVarNumberTypes.VarLongType();
    Type<byte[]> RAW_BYTES = new NetworkBufferRawTypes.RawBytesType(-1);
    Type<short[]> RAW_SHORTS = new NetworkBufferRawTypes.RawShortsType(-1);
    Type<int[]> RAW_INTS = new NetworkBufferRawTypes.RawIntsType(-1);
    Type<long[]> RAW_LONGS = new NetworkBufferRawTypes.RawLongsType(-1);
    Type<float[]> RAW_FLOATS = new NetworkBufferRawTypes.RawFloatsType(-1);
    Type<double[]> RAW_DOUBLES = new NetworkBufferRawTypes.RawDoublesType(-1);
    Type<String> STRING = new NetworkBufferStringTypes.StringType();
    Type<Key> KEY = STRING.transform(Key::key, Key::asString);
    Type<String> STRING_TERMINATED = new NetworkBufferStringTypes.StringTerminatedType();
    Type<String> STRING_IO_UTF8 = new NetworkBufferStringTypes.IOUTF8StringType();
    Type<BinaryTag> NBT = BinaryTagTypeImpl.INSTANCE;
    Type<CompoundBinaryTag> NBT_COMPOUND = BinaryTagTypeImpl.INSTANCE_COMPOUND;
    /** Same wire format as {@link #NBT}, but with the decoded size limited by {@code JvmFlags.NBT_MAX_BYTES}. */
    Type<BinaryTag> UNTRUSTED_NBT = BinaryTagTypeImpl.UNTRUSTED_INSTANCE;
    /** Same wire format as {@link #NBT_COMPOUND}, but with the decoded size limited by {@code JvmFlags.NBT_MAX_BYTES}. */
    Type<CompoundBinaryTag> UNTRUSTED_NBT_COMPOUND = BinaryTagTypeImpl.UNTRUSTED_INSTANCE_COMPOUND;
    /**
     * Same wire format as {@link #NBT}, but writes the keys of every compound, nested ones included,
     * in {@link String#compareTo} order: equal tags give equal bytes in every JVM. {@link #NBT} writes
     * them in the compound's iteration order, which for a compound adventure's {@code BinaryTagIO}
     * read changes from one JVM to the next.
     */
    Type<BinaryTag> SORTED_NBT = BinaryTagTypeImpl.SORTED_INSTANCE;
    Type<Point> BLOCK_POSITION = new NetworkBufferSpatialTypes.BlockPositionType();
    /**
     * A text component in the vanilla network format, a binary tag. Writing one throws
     * {@link IllegalArgumentException} for a component it cannot encode, such as an
     * {@link net.kyori.adventure.text.NBTComponent} or a string over the 65,535 bytes of modified
     * UTF-8 a binary tag string holds, and {@link IllegalStateException} for the data components of a
     * {@code show_item} hover event that are not binary tags already, which need the registries of the
     * buffer: write those to {@link #resizableBuffer(Registries)} or
     * {@link #makeArray(Type, Object, Registries)}.
     */
    Type<Component> COMPONENT = new ComponentNetworkBufferTypeImpl();
    Type<Component> JSON_COMPONENT = new NetworkBufferCodecTypes.JsonComponentType();
    Type<java.util.UUID> UUID = new NetworkBufferPrimitiveTypes.UUIDType();
    Type<Pos> POS = new NetworkBufferSpatialTypes.PosType();

    Type<byte[]> BYTE_ARRAY = new NetworkBufferArrayTypes.ByteArrayType();
    Type<long[]> LONG_ARRAY = new NetworkBufferArrayTypes.LongArrayType();
    Type<int[]> VAR_INT_ARRAY = new NetworkBufferArrayTypes.VarIntArrayType();
    Type<long[]> VAR_LONG_ARRAY = new NetworkBufferArrayTypes.VarLongArrayType();

    Type<BitSet> BITSET = LONG_ARRAY.transform(BitSet::valueOf, BitSet::toLongArray);
    Type<Instant> INSTANT_MS = LONG.transform(Instant::ofEpochMilli, Instant::toEpochMilli);
    Type<PublicKey> PUBLIC_KEY = BYTE_ARRAY.transform(KeyUtils::publicRSAKeyFrom, PublicKey::getEncoded);

    Type<Point> VECTOR3 = new NetworkBufferSpatialTypes.Vector3Type();
    Type<Point> VECTOR3D = new NetworkBufferSpatialTypes.Vector3DType();
    Type<Point> VECTOR3I = new NetworkBufferSpatialTypes.Vector3IType();
    Type<Point> VECTOR3B = new NetworkBufferSpatialTypes.Vector3BType();
    Type<Vec> LP_VECTOR3 = new NetworkBufferSpatialTypes.LpVector3Type();
    Type<float[]> QUATERNION = FixedRawFloats(4);

    Type<@Nullable Component> OPT_CHAT = COMPONENT.optional();
    Type<@Nullable Point> OPT_BLOCK_POSITION = BLOCK_POSITION.optional();

    Type<Direction> DIRECTION = Enum(Direction.class);
    Type<EntityPose> POSE = Enum(EntityPose.class);

    // Combinators

    static <E extends Enum<E>> Type<E> Enum(Class<E> enumClass) {
        final E[] values = enumClass.getEnumConstants();
        return VAR_INT.transform(integer -> {
            Check.argCondition(integer < 0 || integer >= values.length,
                    "Invalid {0} ordinal: {1}", enumClass.getSimpleName(), integer);
            return values[integer];
        }, Enum::ordinal);
    }

    static <E extends Enum<E>> Type<EnumSet<E>> EnumSet(Class<E> enumClass) {
        final E[] values = enumClass.getEnumConstants();
        return new NetworkBufferCollectionTypes.EnumSetType<>(enumClass, values);
    }

    /**
     * Creates a type for a bit set of exactly {@code length} bits, stored in {@code (length + 7) / 8} bytes.
     *
     * @param length the bit count, which must not be negative
     * @return the fixed length bit set type
     */
    static Type<BitSet> FixedBitSet(int length) {
        Check.argCondition(length < 0, "Length cannot be negative: {0}", length);
        return new NetworkBufferCollectionTypes.FixedBitSetType(length);
    }

    /**
     * Creates a type for exactly {@code length} bytes, unlike {@link #RAW_BYTES} which reads every byte left in the buffer.
     *
     * @param length the element count, which must not be negative
     * @return the fixed length byte type
     */
    static Type<byte[]> FixedRawBytes(int length) {
        Check.argCondition(length < 0, "Length cannot be negative: {0}", length);
        return new NetworkBufferRawTypes.RawBytesType(length);
    }

    /**
     * Creates a type for exactly {@code length} shorts, unlike {@link #RAW_SHORTS} which reads every short left in the buffer.
     *
     * @param length the element count, which must not be negative
     * @return the fixed length short type
     */
    static Type<short[]> FixedRawShorts(int length) {
        Check.argCondition(length < 0, "Length cannot be negative: {0}", length);
        return new NetworkBufferRawTypes.RawShortsType(length);
    }

    /**
     * Creates a type for exactly {@code length} ints, unlike {@link #RAW_INTS} which reads every int left in the buffer.
     *
     * @param length the element count, which must not be negative
     * @return the fixed length int type
     */
    static Type<int[]> FixedRawInts(int length) {
        Check.argCondition(length < 0, "Length cannot be negative: {0}", length);
        return new NetworkBufferRawTypes.RawIntsType(length);
    }

    /**
     * Creates a type for exactly {@code length} longs, unlike {@link #RAW_LONGS} which reads every long left in the buffer.
     *
     * @param length the element count, which must not be negative
     * @return the fixed length long type
     */
    static Type<long[]> FixedRawLongs(int length) {
        Check.argCondition(length < 0, "Length cannot be negative: {0}", length);
        return new NetworkBufferRawTypes.RawLongsType(length);
    }

    /**
     * Creates a type for exactly {@code length} floats, unlike {@link #RAW_FLOATS} which reads every float left in the buffer.
     *
     * @param length the element count, which must not be negative
     * @return the fixed length float type
     */
    static Type<float[]> FixedRawFloats(int length) {
        Check.argCondition(length < 0, "Length cannot be negative: {0}", length);
        return new NetworkBufferRawTypes.RawFloatsType(length);
    }

    /**
     * Creates a type for exactly {@code length} doubles, unlike {@link #RAW_DOUBLES} which reads every double left in the buffer.
     *
     * @param length the element count, which must not be negative
     * @return the fixed length double type
     */
    static Type<double[]> FixedRawDoubles(int length) {
        Check.argCondition(length < 0, "Length cannot be negative: {0}", length);
        return new NetworkBufferRawTypes.RawDoublesType(length);
    }

    static <T> Type<T> Lazy(Supplier<Type<T>> supplier) {
        return new NetworkBufferDeferredTypes.LazyType<>(supplier);
    }

    static <T> Type<T> TypedNBT(Codec<T> serializer) {
        return new NetworkBufferCodecTypes.TypedNbtType<>(serializer);
    }

    static <L, R> Type<Either<L, R>> Either(NetworkBuffer.Type<L> left, NetworkBuffer.Type<R> right) {
        return new NetworkBufferCombinatorTypes.EitherType<>(left, right);
    }

    /**
     * A type that contains itself.
     *
     * @param func given the self reference, returns the type that uses it; reading nests the self
     *             reference at most 64 levels deep (the outermost value not counted) and throws
     *             {@link IllegalStateException} past that, writing has no depth limit
     * @param <T>  the type of the value
     * @return the type {@code func} returned
     */
    static <T> Type<T> Recursive(Function<Type<T>, Type<T>> func) {
        return new NetworkBufferDeferredTypes.RecursiveType<>(func).delegate;
    }

    static <T, D> Type<T> Tagged(Type<D> discriminator, Function<? super T, ? extends D> discriminatorFromValue,
                                 Map<? super D, ? extends Type<? extends T>> serializerMap, @Nullable Type<? extends T> fallback) {
        // Map.copyOf does some trickery with the generic bounds here.
        return new NetworkBufferCombinatorTypes.TaggedType<>(discriminator, discriminatorFromValue, Map.copyOf(serializerMap), fallback);
    }

    static <T, D> Type<T> Tagged(Type<D> discriminator, Function<? super T, ? extends D> discriminatorFromValue,
                                 Map<? super D, ? extends Type<? extends T>> serializerMap) {
        return Tagged(discriminator, discriminatorFromValue, serializerMap, null);
    }

    <T extends @UnknownNullability Object> void write(Type<T> type, T value) throws IndexOutOfBoundsException;

    <T extends @UnknownNullability Object> T read(Type<T> type) throws IndexOutOfBoundsException;

    <T extends @UnknownNullability Object> void writeAt(long index, Type<T> type, T value) throws IndexOutOfBoundsException;

    <T extends @UnknownNullability Object> T readAt(long index, Type<T> type) throws IndexOutOfBoundsException;

    void copyTo(long srcOffset, byte[] dest, int destOffset, int length);

    void copyTo(long srcOffset, MemorySegment dest, long destOffset, long length);

    byte[] extractBytes(Consumer<NetworkBuffer> extractor);

    NetworkBuffer clear();

    long writeIndex();

    long readIndex();

    NetworkBuffer writeIndex(long writeIndex);

    NetworkBuffer readIndex(long readIndex);

    NetworkBuffer index(long readIndex, long writeIndex);

    long advanceWrite(long length);

    long advanceRead(long length);

    long readableBytes();

    long writableBytes();

    long capacity();

    @Contract(pure = true, value = "-> new")
    NetworkBuffer readOnly();

    boolean isReadOnly();

    void resize(long newSize);

    void ensureWritable(long length) throws IndexOutOfBoundsException;

    void ensureReadable(long length) throws IndexOutOfBoundsException;

    void compact();

    NetworkBuffer copy(long index, long length, long readIndex, long writeIndex);

    default NetworkBuffer copy(long index, long length) {
        return copy(index, length, readIndex(), writeIndex());
    }

    int readChannel(ReadableByteChannel channel) throws IOException;

    boolean writeChannel(WritableByteChannel channel) throws IOException;

    /**
     * Encrypts or decrypts {@code [start, start + length)} in place.
     *
     * @param cipher a stream mode cipher whose output is always as long as its input, such as
     *               {@code AES/CFB8/NoPadding}
     * @param start  the index of the first byte
     * @param length the number of bytes
     */
    void cipher(Cipher cipher, long start, long length);

    long compress(long start, long length, NetworkBuffer output);

    long decompress(long start, long length, NetworkBuffer output) throws DataFormatException;

    @Nullable Registries registries();

    void registries(@Nullable Registries registries);

    /**
     * A slice is an operation where the backing memory is used for the new buffer. Consider it a view over the backing memory region.
     *
     * @param offset     the position to start the slice
     * @param byteLength the length of the slice, equal to {@link #capacity()}
     * @param readIndex  the read index
     * @param writeIndex the write index
     * @return a new buffer
     * @throws IllegalArgumentException if this buffer is a dummy
     * @apiNote With resized segments, you could observe behavior where you may see stale data or close arenas.
     * To avoid this, do not use resizable segments in a way where they are written or resized like {@link #resize(long)}.
     * @implNote {@link #registries()} are copied into the new buffer
     */
    @Contract(pure = true, value = "_, _, _, _ -> new")
    NetworkBuffer slice(long offset, long byteLength, long readIndex, long writeIndex);

    /**
     * Creates a new {@link IOView} of this buffer.
     * <br>
     * Useful to interface with API's that support {@link DataInput} or {@link DataOutput}.
     *
     * @return the io view.
     */
    @Deprecated(forRemoval = true) // Use NetworkBuffer directly, no DataInput or DataOutput replacement is provided.
    @ApiStatus.Experimental
    @Contract(pure = true, value = "-> new")
    IOView ioView();

    interface Type<T extends @UnknownNullability Object> {
        void write(NetworkBuffer buffer, T value);

        T read(NetworkBuffer buffer);

        default long sizeOf(T value, @Nullable Registries registries) {
            return NetworkBufferTypeImpl.sizeOf(this, value, registries);
        }

        default long sizeOf(T value) {
            return sizeOf(value, null);
        }

        default <S> Type<S> transform(Function<T, S> to, Function<S, T> from) {
            return new NetworkBufferCombinatorTypes.TransformType<>(this, to, from);
        }

        default <V> Type<Map<T, V>> mapValue(Type<V> valueType, int maxSize) {
            return new NetworkBufferCollectionTypes.MapType<>(this, valueType, maxSize);
        }

        default <V> Type<Map<T, V>> mapValue(Type<V> valueType) {
            return mapValue(valueType, Integer.MAX_VALUE);
        }

        default Type<List<T>> list(int maxSize) {
            return new NetworkBufferCollectionTypes.ListType<>(this, maxSize);
        }

        default Type<List<T>> list() {
            return list(Integer.MAX_VALUE);
        }

        default Type<Set<T>> set(int maxSize) {
            return new NetworkBufferCollectionTypes.SetType<>(this, maxSize);
        }

        default Type<Set<T>> set() {
            return set(Integer.MAX_VALUE);
        }

        default Type<@Nullable T> optional() {
            return new NetworkBufferCombinatorTypes.OptionalType<>(this);
        }

        default <R, TR extends R> Type<R> unionType(Function<T, NetworkBuffer.Type<TR>> serializers, Function<R, ? extends T> keyFunc) {
            return new NetworkBufferCombinatorTypes.UnionType<>(this, keyFunc, serializers);
        }

        /**
         * A value preceded by its encoded length in bytes, as a VarInt. Reading only reads the value
         * within the declared length, and fails when the value does not take exactly that many bytes.
         *
         * @param maxLength the most encoded bytes accepted, when reading and when writing
         * @return the length prefixed type
         */
        default Type<T> lengthPrefixed(int maxLength) {
            return new NetworkBufferCombinatorTypes.LengthPrefixedType<>(this, maxLength);
        }

        /**
         * This type limited to {@code maxLength} encoded bytes. Reading checks the length after the
         * value was decoded, so the limit does not bound what decoding allocates.
         *
         * @param maxLength the most encoded bytes accepted
         * @return the limited type
         */
        default Type<T> maxLength(long maxLength) {
            return new NetworkBufferCombinatorTypes.MaxLength<>(this, maxLength);
        }
    }

    static Builder builder(long size) {
        return new NetworkBufferImpl.Builder(size);
    }

    static NetworkBuffer staticBuffer(long size, Registries registries) {
        return builder(size).registry(registries).build();
    }

    static NetworkBuffer staticBuffer(long size) {
        return builder(size).build();
    }

    static NetworkBuffer resizableBuffer(long initialSize, Registries registries) {
        return builder(initialSize)
                .autoResize(AutoResize.DOUBLE)
                .registry(registries)
                .build();
    }

    static NetworkBuffer resizableBuffer(int initialSize) {
        return builder(initialSize)
                .autoResize(AutoResize.DOUBLE)
                .build();
    }

    static NetworkBuffer resizableBuffer(Registries registries) {
        return resizableBuffer(256, registries);
    }

    static NetworkBuffer resizableBuffer() {
        return resizableBuffer(256);
    }

    static NetworkBuffer wrap(MemorySegment segment, long readIndex, long writeIndex, @Nullable Registries registries) {
        return NetworkBufferImpl.wrap(segment, readIndex, writeIndex, registries);
    }

    static NetworkBuffer wrap(MemorySegment segment, long readIndex, long writeIndex) {
        return wrap(segment, readIndex, writeIndex, null);
    }

    static NetworkBuffer wrap(byte[] bytes, int readIndex, int writeIndex, @Nullable Registries registries) {
        return wrap(MemorySegment.ofArray(bytes), readIndex, writeIndex, registries);
    }

    static NetworkBuffer wrap(byte[] bytes, int readIndex, int writeIndex) {
        return wrap(bytes, readIndex, writeIndex, null);
    }

    sealed interface Builder permits NetworkBufferImpl.Builder {
        Builder autoResize(@Nullable AutoResize autoResize);

        Builder registry(@Nullable Registries registries);

        NetworkBuffer build();
    }

    @FunctionalInterface
    interface AutoResize {
        AutoResize DOUBLE = (capacity, targetSize) -> Math.max(capacity * 2, targetSize);

        long resize(long capacity, long targetSize);
    }

    /**
     * Self-contained interface
     * that extends {@link DataInput} and {@link DataOutput} for mostly reading/writing binary tags.
     * <br>
     * You can access the io view of a network buffer with {@link NetworkBuffer#ioView()}
     * <br>
     * This interface is separate from {@link NetworkBuffer}
     * because we don't want DataInput and DataOutput to be part of the public API.
     * You should use {@link NetworkBuffer} instead where possible.
     *
     * @apiNote You should never rely on the identity of {@link IOView} as it is a value class candidate.
     * @implNote This implementation removes checked exceptions as the backing {@link NetworkBuffer} would not throw {@link IOException}'s.
     * Also {@link #readLine()} is not implemented as it's already deprecated in {@link java.io.DataInputStream}.
     */
    @Deprecated(forRemoval = true) // Use NetworkBuffer directly, no DataInput or DataOutput replacement is provided.
    @SuppressWarnings("removal")
    @ApiStatus.Experimental
    sealed interface IOView extends DataInput, DataOutput permits NetworkBufferImpl.IOView {

        @Deprecated(forRemoval = true)
        @Override
        @Contract("-> fail")
        default String readLine() {
            throw new UnsupportedOperationException("Deprecated method readLine() called, not implemented");
        }

        @Override
        void readFully(byte[] bytes);

        @Override
        void readFully(byte[] bytes, int off, int len);

        @Override
        int skipBytes(int n);

        @Override
        boolean readBoolean();

        @Override
        byte readByte();

        @Override
        int readUnsignedByte();

        @Override
        short readShort();

        @Override
        int readUnsignedShort();

        @Override
        char readChar();

        @Override
        int readInt();

        @Override
        long readLong();

        @Override
        float readFloat();

        @Override
        double readDouble();

        @Override
        String readUTF();

        @Override
        void write(int lower);

        @Override
        void write(byte[] bytes);

        @Override
        void write(byte[] bytes, int off, int len);

        @Override
        void writeBoolean(boolean value);

        @Override
        void writeByte(int value);

        @Override
        void writeShort(int value);

        @Override
        void writeChar(int value);

        @Override
        void writeInt(int value);

        @Override
        void writeLong(long value);

        @Override
        void writeFloat(float value);

        @Override
        void writeDouble(double value);

        @Override
        void writeBytes(String value);

        @Override
        void writeChars(String value);

        @Override
        void writeUTF(String value);
    }

    static byte[] makeArray(Consumer<NetworkBuffer> writing, @Nullable Registries registries) {
        NetworkBuffer buffer = resizableBuffer(256, registries);
        writing.accept(buffer);
        return buffer.read(RAW_BYTES);
    }

    static byte[] makeArray(Consumer<NetworkBuffer> writing) {
        return makeArray(writing, null);
    }

    static <T> byte[] makeArray(Type<T> type, T value, @Nullable Registries registries) {
        return makeArray(buffer -> buffer.write(type, value), registries);
    }

    static <T> byte[] makeArray(Type<T> type, T value) {
        return makeArray(type, value, null);
    }

    static void copy(NetworkBuffer srcBuffer, long srcOffset,
                     NetworkBuffer dstBuffer, long dstOffset, long length) {
        NetworkBufferImpl.copy(srcBuffer, srcOffset, dstBuffer, dstOffset, length);
    }

    /**
     * Compares the entire backing memory, capacity included, byte for byte; read and write indices
     * are ignored. To compare readable content, copy or slice both buffers to
     * {@code [readIndex, writeIndex)} first.
     */
    static boolean equals(NetworkBuffer buffer1, NetworkBuffer buffer2) {
        return NetworkBufferImpl.equals(buffer1, buffer2);
    }
}
