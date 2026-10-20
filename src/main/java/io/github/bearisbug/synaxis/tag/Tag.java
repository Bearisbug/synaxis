package io.github.bearisbug.synaxis.tag;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.registry.Registries;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Represents a key to retrieve or change a value.
 * <p>
 * All tags are serializable.
 * <p>
 * Build a tag once, in a {@code static final} field, and reuse it. {@link #ItemStack(String, Registries)},
 * {@link #Component(String, Registries)}, {@link #Structure(String, TagSerializer)} and {@link #map(Function, Function)}
 * create new functions on every call: two tags built that way from the same key are not equal and share no cached
 * value, every read decodes the NBT again. A tag mapped from a {@link #Transient(String)} one reads only what the
 * very same tag instance wrote.
 *
 * @param <T> the tag type
 */
public sealed interface Tag<T extends @UnknownNullability Object> permits TagImpl {
    static Tag<Byte> Byte(String key) {
        return TagImpl.tag(key, Serializers.BYTE);
    }

    static Tag<Boolean> Boolean(String key) {
        return TagImpl.tag(key, Serializers.BOOLEAN);
    }

    static Tag<Short> Short(String key) {
        return TagImpl.tag(key, Serializers.SHORT);
    }

    static Tag<Integer> Integer(String key) {
        return TagImpl.tag(key, Serializers.INT);
    }

    static Tag<Long> Long(String key) {
        return TagImpl.tag(key, Serializers.LONG);
    }

    static Tag<Float> Float(String key) {
        return TagImpl.tag(key, Serializers.FLOAT);
    }

    static Tag<Double> Double(String key) {
        return TagImpl.tag(key, Serializers.DOUBLE);
    }

    static Tag<String> String(String key) {
        return TagImpl.tag(key, Serializers.STRING);
    }

    static Tag<UUID> UUID(String key) {
        return TagImpl.tag(key, Serializers.UUID);
    }

    static Tag<ItemStack> ItemStack(String key, Registries registries) {
        return TagImpl.tag(key, Serializers.item(registries));
    }

    static Tag<Component> Component(String key, Registries registries) {
        return TagImpl.tag(key, Serializers.component(registries));
    }

    /**
     * Creates a flexible tag able to read and write any {@link BinaryTag} objects.
     * <p>
     * Specialized tags are recommended if the type is known as conversion will be required both way (read and write).
     * <p>
     * A compound set with this tag on a {@link TagHandler} is separated key by key into the tags of the handler, which
     * registers every key in a process wide, never shrinking index: <b>must not be used with untrusted data</b>, see
     * {@link TagHandler#fromCompound(CompoundBinaryTag)}.
     */
    static Tag<BinaryTag> NBT(String key) {
        return TagImpl.tag(key, Serializers.NBT_ENTRY);
    }

    /**
     * Creates a tag containing multiple fields.
     * <p>
     * Those fields cannot be modified from an outside tag. (This is to prevent the backed object from becoming out of sync)
     * <p>
     * <b>Must not be used with untrusted data.</b> Reading this tag runs the whole sub compound
     * through {@link TagHandler#fromCompound(net.kyori.adventure.nbt.CompoundBinaryTag)}, which
     * registers every key it finds in a process wide, never shrinking index: a compound coming from
     * a client can grow that index without bound (audit F49). Read client NBT key by key instead,
     * the way the item components do.
     *
     * @param key        the tag key
     * @param serializer the tag serializer
     * @param <T>        the tag type
     * @return the created tag
     */
    static <T> Tag<T> Structure(String key, TagSerializer<T> serializer) {
        return TagImpl.fromSerializer(key, serializer);
    }

    /**
     * Specialized Structure tag affecting the src of the handler (i.e. overwrite all its data).
     * <p>
     * Must be used with care, and like {@link #Structure(String, TagSerializer)}
     * <b>must not be used with untrusted data</b>.
     */
    static <T> Tag<T> View(TagSerializer<T> serializer) {
        return TagImpl.view(serializer);
    }

    @ApiStatus.Experimental
    static <T extends Record> Tag<T> Structure(String key, Class<T> type) {
        return Structure(key, TagRecord.serializer(type));
    }

    @ApiStatus.Experimental
    static <T extends Record> Tag<T> View(Class<T> type) {
        return View(TagRecord.serializer(type));
    }

    /**
     * Creates a transient tag with the specified key. This tag does not get serialized
     * to NBT (Named Binary Tag) format and is not sent to the client. Unlike other tags,
     * which are serialized, transient tags are used for temporary data
     * that only needs to exist on the server side.
     * <p>
     * Only a {@link TagHandler} holds a transient value. Whatever is NBT underneath (an item stack, a block,
     * {@code CustomData}) has nowhere to keep it: writing one there removes the key.
     *
     * @param <T> The type of the tag's value.
     * @param key The key.
     * @return A transient tag with the key.
     */
    @SuppressWarnings("unchecked")
    static <T> Tag<T> Transient(String key) {
        return (Tag<T>) TagImpl.tag(key, Serializers.TRANSIENT);
    }

    /**
     * Returns the key for the Tag
     * <br>
     * Same key specified during the creation; a view has the empty key.
     * @return the key to use
     */
    String key();

    /**
     * Creates this tag with a default value, read where the tag has no value.
     *
     * @param defaultValue called on every read that finds no value
     * @return the tag with the default value
     */
    @Contract(value = "_ -> new", pure = true)
    Tag<T> defaultValue(Supplier<T> defaultValue);

    /**
     * Creates this tag with a default value, read where the tag has no value. Every such read returns this very
     * instance: a mutable default takes {@link #defaultValue(Supplier)}.
     *
     * @param defaultValue the default value
     * @return the tag with the default value
     */
    @Contract(value = "_ -> new", pure = true)
    Tag<T> defaultValue(T defaultValue);

    /**
     * Creates a tag of another type over the same key, read through {@code readMap} and written through
     * {@code writeMap}. Each call creates a new tag that shares no cached value with another, see the class
     * description.
     *
     * @param readMap  turns a value of this tag into one of the new tag
     * @param writeMap turns a value of the new tag into one of this tag
     * @param <R>      the type of the new tag
     * @return the mapped tag
     */
    @Contract(value = "_, _ -> new", pure = true)
    <R> Tag<R> map(Function<T, R> readMap,
                   Function<R, T> writeMap);

    /**
     * Creates a tag holding a list of values of this tag, over the same key. The list tag has no default value, it
     * does not take the one of this tag.
     *
     * @return the list tag
     */
    @Contract(value = "-> new", pure = true)
    Tag<List<T>> list();

    /**
     * Creates this tag under the given path of nested compounds.
     *
     * @param path the keys of the nested compounds, outermost first; null or an empty array for no path
     * @return the tag under the path
     * @throws IllegalArgumentException if a key of the path is null or empty
     */
    @Contract(value = "_ -> new", pure = true)
    Tag<T> path(String @Nullable ... path);

    /**
     * Reads this tag from its own key of the given compound, or the whole compound for a view tag.
     * <p>
     * The {@link #path(String...) path} is not applied: a caller holding the root compound descends the path first,
     * the way blocks and the custom data of items do.
     *
     * @param nbt the compound holding the key of this tag
     * @return the value, or the default value when the key is absent or holds another type
     */
    T read(CompoundBinaryTag nbt);

    /**
     * Writes this tag to its own key of the given compound builder, or over the whole compound for a view tag.
     * <p>
     * The {@link #path(String...) path} is not applied: a caller building the root compound descends the path first,
     * the way blocks and the custom data of items do.
     *
     * @param nbtCompound the builder of the compound holding the key of this tag
     * @param value       the value, null to remove the key
     */
    void write(CompoundBinaryTag.Builder nbtCompound, T value);

    /**
     * Whether this tag is a view ({@link #View(TagSerializer)}): it reads and writes the whole compound rather than
     * one key of it.
     *
     * @return true for a view
     */
    boolean isView();

    /**
     * Whether a value cached for {@code other} is a value of this tag as well, so that it can be returned without
     * decoding the NBT again.
     *
     * @param other the other tag
     * @return true if the two tags share their values
     */
    @ApiStatus.Internal
    boolean shareValue(Tag<?> other);

    /**
     * Gets the default value of this tag.
     *
     * @return the default value, null if there is none
     */
    @ApiStatus.Internal
    T createDefault();

    /**
     * Copies a value before it is stored, for the tags of a type that is mutable, such as a list.
     *
     * @param value the value
     * @return the copy to store, {@code value} itself for an immutable type
     */
    @ApiStatus.Internal
    T copyValue(T value);
}
