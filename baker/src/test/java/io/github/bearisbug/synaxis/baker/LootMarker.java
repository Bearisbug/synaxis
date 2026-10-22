package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.tag.Tag;
import net.kyori.adventure.key.Key;

import java.util.List;

/**
 * The handler of the fixture save's {@code synaxis:loot} marker: the client gets the {@code table} tag of its block
 * entity.
 */
public final class LootMarker implements BlockHandler {
    private static final Key KEY = Key.key("synaxis:loot");
    private static final List<Tag<?>> CLIENT_TAGS = List.of(Tag.String("table"));

    @Override
    public Key getKey() {
        return KEY;
    }

    @Override
    public List<Tag<?>> getBlockEntityTags() {
        return CLIENT_TAGS;
    }
}
