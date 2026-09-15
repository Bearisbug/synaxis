package io.github.bearisbug.synaxis.tag;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

/**
 * Reads and writes a single {@link Tag} directly against an NBT compound.
 * <p>
 * {@link TagHandler#fromCompound(CompoundBinaryTag)} separates a whole compound into tags, which
 * hands every key and path segment it contains a permanent index in the JVM wide tag index. NBT
 * coming from a client must be accessed through here instead, so that only the keys of the tag
 * being read or written are ever indexed.
 */
@ApiStatus.Internal
public final class TagNbtAccess {

    private TagNbtAccess() {
    }

    public static <T> @UnknownNullability T read(CompoundBinaryTag nbt, Tag<T> tag) {
        final TagImpl.PathEntry[] paths = ((TagImpl<?>) tag).path();
        CompoundBinaryTag local = nbt;
        if (paths != null) {
            for (TagImpl.PathEntry path : paths) {
                if (!(local.get(path.name()) instanceof CompoundBinaryTag child))
                    return tag.createDefault(); // Path is absent
                local = child;
            }
        }
        return tag.read(local);
    }

    public static <T> CompoundBinaryTag write(CompoundBinaryTag nbt, Tag<T> tag, @Nullable T value) {
        final TagImpl.PathEntry[] paths = ((TagImpl<?>) tag).path();
        if (paths == null) return writeEntry(nbt, tag, value);
        final CompoundBinaryTag[] parents = new CompoundBinaryTag[paths.length];
        CompoundBinaryTag local = nbt;
        for (int i = 0; i < paths.length; i++) {
            parents[i] = local;
            local = local.get(paths[i].name()) instanceof CompoundBinaryTag child ? child : CompoundBinaryTag.empty();
        }
        CompoundBinaryTag result = writeEntry(local, tag, value);
        for (int i = paths.length - 1; i >= 0; i--) {
            final String name = paths[i].name();
            final CompoundBinaryTag.Builder parent = CompoundBinaryTag.builder().put(parents[i]);
            // An emptied path is dropped, as the compound built by a tag handler does
            if (result.isEmpty() && !TagFlags.SERIALIZE_EMPTY_COMPOUND) parent.remove(name);
            else parent.put(name, result);
            result = parent.build();
        }
        return result;
    }

    private static <T> CompoundBinaryTag writeEntry(CompoundBinaryTag nbt, Tag<T> tag, @Nullable T value) {
        final CompoundBinaryTag.Builder builder = CompoundBinaryTag.builder();
        // A view tag overwrites the whole compound instead of one of its entries
        if (!tag.isView()) builder.put(nbt);
        tag.write(builder, value);
        return builder.build();
    }
}
