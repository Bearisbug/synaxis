package io.github.bearisbug.synaxis.item.component;

import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.util.RGBLike;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.color.Color;
import io.github.bearisbug.synaxis.component.DataComponent;
import io.github.bearisbug.synaxis.component.DataComponents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static java.util.Map.entry;
import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ColorTest extends AbstractItemComponentRegistriesTest<RGBLike> {
    // This is not a test, but it creates a compile error if the component type is changed away from Integer,
    // as a reminder that tests should be added for that new component type.
    private static final List<DataComponent<RGBLike>> SHARED_COMPONENTS = List.of(
            DataComponents.MAP_COLOR
    );

    @Override
    protected DataComponent<RGBLike> component() {
        return SHARED_COMPONENTS.getFirst();
    }

    @Override
    protected List<Map.Entry<String, RGBLike>> directReadWriteEntries() {
        return List.of(
                entry("simple", new Color(0x123456))
        );
    }

    @Test
    void namedTextColor() {
        var tag = assertOk(DataComponents.MAP_COLOR.encode(Transcoder.NBT, NamedTextColor.YELLOW));
        assertEquals(IntBinaryTag.intBinaryTag(16777045), tag);
    }
}
