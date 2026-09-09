package dev.gameengine.tag;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagType;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.ByteArrayBinaryTag;
import net.kyori.adventure.nbt.ByteBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.DoubleBinaryTag;
import net.kyori.adventure.nbt.FloatBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.kyori.adventure.nbt.LongBinaryTag;
import net.kyori.adventure.nbt.ShortBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import dev.gameengine.ServerFlag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Map.entry;

/**
 * Handles conversion of {@link BinaryTag} subtypes into one or multiple primitive {@link Tag tags}.
 */
final class TagNbtSeparator {
    static final Map<BinaryTagType<?>, Function<String, Tag<?>>> SUPPORTED_TYPES = Map.ofEntries(
            entry(BinaryTagTypes.BYTE, Tag::Byte),
            entry(BinaryTagTypes.SHORT, Tag::Short),
            entry(BinaryTagTypes.INT, Tag::Integer),
            entry(BinaryTagTypes.LONG, Tag::Long),
            entry(BinaryTagTypes.FLOAT, Tag::Float),
            entry(BinaryTagTypes.DOUBLE, Tag::Double),
            entry(BinaryTagTypes.STRING, Tag::String));

    static void separate(CompoundBinaryTag nbtCompound, Consumer<Entry<?>> consumer) {
        for (var ent : nbtCompound) {
            convert(new ArrayList<>(), ent.getKey(), ent.getValue(), consumer);
        }
    }

    static void separate(String key, BinaryTag nbt, Consumer<Entry<?>> consumer) {
        convert(new ArrayList<>(), key, nbt, consumer);
    }

    static Entry<?> separateSingle(String key, BinaryTag nbt) {
        assert !(nbt instanceof CompoundBinaryTag);
        AtomicReference<Entry<?>> entryRef = new AtomicReference<>();
        convert(new ArrayList<>(), key, nbt, entry -> {
            assert entryRef.getPlain() == null : "Multiple entries found for nbt tag: " + key + " -> " + nbt;
            entryRef.setPlain(entry);
        });
        var entry = entryRef.getPlain();
        assert entry != null;
        return entry;
    }

    @SuppressWarnings("unchecked")
    private static void convert(List<String> path, String key, BinaryTag nbt, Consumer<Entry<?>> consumer) {
        var tagFunction = SUPPORTED_TYPES.get(nbt.type());
        if (tagFunction != null) {
            Tag<?> tag = tagFunction.apply(key);
            consumer.accept(makeEntry(path, (Tag<Object>) tag, nbtValueFromTag(nbt)));
        } else if (nbt instanceof CompoundBinaryTag nbtCompound) {
            if (nbtCompound.isEmpty()) {
                if (ServerFlag.SERIALIZE_EMPTY_COMPOUND || path.isEmpty()) {
                    consumer.accept(makeEntry(path, Tag.NBT(key), nbt));
                }
            } else {
                for (var ent : nbtCompound) {
                    var newPath = new ArrayList<>(path);
                    newPath.add(key);
                    convert(newPath, ent.getKey(), ent.getValue(), consumer);
                }
            }
        } else if (nbt instanceof ListBinaryTag nbtList) {
            tagFunction = SUPPORTED_TYPES.get(nbtList.elementType());
            if (tagFunction == null) {
                // Invalid list subtype, fallback to nbt
                consumer.accept(makeEntry(path, Tag.NBT(key), nbt));
            } else {
                try {
                    var tag = tagFunction.apply(key).list();
                    Object[] values = new Object[nbtList.size()];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = nbtValueFromTag(nbtList.get(i));
                    }
                    consumer.accept(makeEntry(path, (Tag<? super List<Object>>) tag, List.of(values)));
                } catch (Exception e) {
                    e.printStackTrace();
                    consumer.accept(makeEntry(path, Tag.NBT(key), nbt));
                }
            }
        } else {
            // TODO array support
            consumer.accept(makeEntry(path, Tag.NBT(key), nbt));
        }
    }

    private static Object nbtValueFromTag(BinaryTag tag) {
        return switch (tag) {
            case ByteBinaryTag byteTag -> byteTag.value();
            case ShortBinaryTag shortTag -> shortTag.value();
            case IntBinaryTag intTag -> intTag.value();
            case LongBinaryTag longTag -> longTag.value();
            case FloatBinaryTag floatTag -> floatTag.value();
            case DoubleBinaryTag doubleTag -> doubleTag.value();
            case ByteArrayBinaryTag byteArrayTag -> byteArrayTag.value();
            case StringBinaryTag stringTag -> stringTag.value();
            case IntArrayBinaryTag intArrayTag -> intArrayTag.value();
            case LongArrayBinaryTag longArrayTag -> longArrayTag.value();
            default -> throw new UnsupportedOperationException("Unsupported NBT type: " + tag.getClass());
        };
    }

    private static <T> Entry<?> makeEntry(List<String> path, Tag<T> tag, T value) {
        return new Entry<>(tag.path(path.toArray(String[]::new)), value);
    }

    record Entry<T>(TagImpl<T> tag, T value) {
        public Entry(Tag<T> tag, T value) {
            this((TagImpl<T>) tag, value);
        }

        void write(TagWritable writer) {
            writer.setTag(tag, value);
        }
    }
}
