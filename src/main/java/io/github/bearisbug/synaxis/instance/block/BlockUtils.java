package io.github.bearisbug.synaxis.instance.block;

import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.tag.Tag;
import io.github.bearisbug.synaxis.tag.TagNbtAccess;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

public class BlockUtils {

    private final Block.Getter instance;
    private final Point position;

    public BlockUtils(Block.Getter instance, Point position) {
        this.instance = instance;
        this.position = position;
    }

    public BlockUtils getRelativeTo(int x, int y, int z) {
        return new BlockUtils(instance, position.add(x, y, z));
    }

    public BlockUtils above() {
        return getRelativeTo(0, 1, 0);
    }

    public BlockUtils below() {
        return getRelativeTo(0, -1, 0);
    }

    public BlockUtils north() {
        return getRelativeTo(0, 0, -1);
    }

    public BlockUtils east() {
        return getRelativeTo(1, 0, 0);
    }

    public BlockUtils south() {
        return getRelativeTo(0, 0, 1);
    }

    public BlockUtils west() {
        return getRelativeTo(-1, 0, 0);
    }

    public Block getBlock() {
        return instance.getBlock(position);
    }

    @SuppressWarnings("NonOverridingEquals")
    @Deprecated(forRemoval = true)
    public boolean equals(Block block) {
        return getBlock().compare(block);
    }

    /**
     * Parses the property list of a block state, the {@code [k=v,...]} of {@code ns:id[k=v,...]}. Keys and values
     * are trimmed and blank entries skipped; a string not in brackets has no properties.
     *
     * @param query the property list, brackets included
     * @return the properties, in the order of the list
     * @throws IllegalArgumentException if an entry that is not blank has no {@code =} or an empty key, or names a
     *                                  key an earlier entry names
     */
    public static Map<String, String> parseProperties(String query) {
        if (!query.startsWith("[") || !query.endsWith("]")) return Map.of();
        if (query.length() == 2) return Map.of();

        int entries = 1;
        for (int i = 0; i < query.length(); i++) {
            if (query.charAt(i) == ',') entries++;
        }
        String[] keys = new String[entries];
        String[] values = new String[entries];
        int entryCount = 0;

        final int length = query.length() - 1;
        int start = 1;
        int index = 1;
        while (index <= length) {
            if (query.charAt(index) == ',' || index == length) {
                final int equalIndex = query.indexOf('=', start);
                if (equalIndex != -1 && equalIndex < index) {
                    final String key = query.substring(start, equalIndex).trim();
                    if (key.isEmpty()) throw malformedProperty(query, start, index);
                    for (int i = 0; i < entryCount; i++) {
                        if (keys[i].equals(key)) throw malformedProperty(query, start, index);
                    }
                    final String value = query.substring(equalIndex + 1, index).trim();
                    keys[entryCount] = key;
                    values[entryCount++] = value;
                } else if (!query.substring(start, index).isBlank()) {
                    throw malformedProperty(query, start, index);
                }
                start = index + 1;
            }
            index++;
        }
        return new Object2ObjectArrayMap<>(keys, values, entryCount);
    }

    private static IllegalArgumentException malformedProperty(String query, int start, int end) {
        return new IllegalArgumentException("malformed block state property '" + query.substring(start, end).trim()
                + "' in " + query);
    }

    public static @Nullable CompoundBinaryTag extractClientNbt(Block block) {
        if (block.blockEntityType() == null) return null;
        // Append handler tags
        final BlockHandler handler = block.handler();
        final CompoundBinaryTag blockNbt = Objects.requireNonNullElseGet(block.nbt(), CompoundBinaryTag::empty);
        if (handler != null) {
            // Extract explicitly defined tags and keep the rest server-side
            CompoundBinaryTag clientNbt = CompoundBinaryTag.empty();
            for (Tag<?> tag : handler.getBlockEntityTags()) {
                clientNbt = copyTag(blockNbt, clientNbt, tag);
            }
            return clientNbt;
        }
        // Complete nbt shall be sent if the block has no handler
        // Necessary to support all vanilla blocks
        return blockNbt;
    }

    // An absent value is skipped rather than written: writing null for a view tag would empty the compound
    private static <T> CompoundBinaryTag copyTag(CompoundBinaryTag from, CompoundBinaryTag to, Tag<T> tag) {
        final T value = TagNbtAccess.read(from, tag);
        return value == null ? to : TagNbtAccess.write(to, tag, value);
    }
}
