package io.github.bearisbug.synaxis.component;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.codec.TranscoderProxy;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.utils.Unit;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DataComponentMapTest {

    private static NetworkBuffer patch(int added, int removed, int... ids) {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer();
        buffer.write(NetworkBuffer.VAR_INT, added);
        buffer.write(NetworkBuffer.VAR_INT, removed);
        for (int id : ids) buffer.write(NetworkBuffer.VAR_INT, id);
        return buffer;
    }

    /**
     * A patch holding a container of items, each of which carries a patch again, nested {@code depth} times.
     */
    private static NetworkBuffer nestedContainerPatch(int depth) {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer();
        for (int i = 0; i < depth; i++) {
            buffer.write(NetworkBuffer.VAR_INT, 1); // added
            buffer.write(NetworkBuffer.VAR_INT, 0); // removed
            buffer.write(NetworkBuffer.VAR_INT, DataComponents.CONTAINER.id());
            buffer.write(NetworkBuffer.VAR_INT, 1); // container size
            buffer.write(NetworkBuffer.BOOLEAN, true); // the slot holds an item
            buffer.write(NetworkBuffer.VAR_INT, Material.STONE.id());
            buffer.write(NetworkBuffer.VAR_INT, 1); // item amount
        }
        buffer.write(NetworkBuffer.VAR_INT, 0); // added
        buffer.write(NetworkBuffer.VAR_INT, 0); // removed
        return buffer;
    }

    @Test
    void networkPatchRejectsInvalidCounts() {
        assertThrows(RuntimeException.class, () -> patch(-1, 0).read(DataComponent.PATCH_NETWORK_TYPE));
        assertThrows(RuntimeException.class, () -> patch(0, -1).read(DataComponent.PATCH_NETWORK_TYPE));
        assertThrows(RuntimeException.class, () -> patch(257, 0).read(DataComponent.PATCH_NETWORK_TYPE));
        assertThrows(RuntimeException.class, () -> patch(0, 257).read(DataComponent.PATCH_NETWORK_TYPE));
    }

    @Test
    void networkPatchRejectsUnknownRemovedComponent() {
        assertThrows(RuntimeException.class,
                () -> patch(0, 1, Integer.MAX_VALUE).read(DataComponent.PATCH_NETWORK_TYPE));
    }

    @Test
    void networkPatchRejectsDuplicateRemovedComponent() {
        final int id = DataComponents.CUSTOM_NAME.id();
        assertThrows(RuntimeException.class,
                () -> patch(0, 2, id, id).read(DataComponent.PATCH_NETWORK_TYPE));
    }

    @Test
    void networkPatchRejectsDeeplyNestedComponents() {
        final var error = assertThrows(IllegalStateException.class,
                () -> nestedContainerPatch(128).read(DataComponent.PATCH_NETWORK_TYPE));
        assertEquals("Components nested deeper than 64 levels", error.getMessage());
        // The rejected read must not leave the depth accounting behind for the next read on this thread.
        assertDoesNotThrow(() -> patch(0, 0).read(DataComponent.PATCH_NETWORK_TYPE));
    }

    @Test
    void codecRejectsComponentDecodedToNull() {
        // A null value means "component removed" in a patch, so it must not be what a codec decodes to. A transcoder
        // that reads every int as null makes a registered component decode to null; registering a component here is
        // not an option: other test classes go through every registered component concurrently.
        final Transcoder<Object> intsReadAsNull = new TranscoderProxy<>() {
            @Override
            public Transcoder<Object> delegate() {
                return Transcoder.JAVA;
            }

            @Override
            public Result<Integer> getInt(Object value) {
                return new Result.Ok<>(null);
            }
        };
        final Result<DataComponentMap> result = DataComponent.PATCH_CODEC.decode(intsReadAsNull,
                Map.of(DataComponents.MAX_STACK_SIZE.name(), 1));

        assertEquals("minecraft:max_stack_size: null", assertInstanceOf(Result.Error.class, result).message());
    }

    @Test
    void codecNamesTheComponentAndTheValueItCannotDecode() {
        final Map<String, Object> input = Map.of(DataComponents.RARITY.name(), "legendary");
        final Result<DataComponentMap> result = DataComponent.PATCH_CODEC.decode(Transcoder.JAVA, input);

        assertEquals("minecraft:rarity: IllegalArgumentException: No enum constant "
                        + "io.github.bearisbug.synaxis.item.component.ItemRarity.LEGENDARY",
                assertInstanceOf(Result.Error.class, result).message());
    }

    /** null marks a removal inside a map, so setting it is refused rather than silently removing the component. */
    @Test
    void settingANullValueIsRefused() {
        final NullPointerException patch = assertThrows(NullPointerException.class,
                () -> DataComponentMap.patchBuilder().set(DataComponents.MAX_STACK_SIZE, null));
        assertTrue(patch.getMessage().contains(DataComponents.MAX_STACK_SIZE.name()), patch::getMessage);
        assertThrows(NullPointerException.class, () -> DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, null));
        assertThrows(NullPointerException.class, () -> DataComponentMap.EMPTY.set(DataComponents.MAX_STACK_SIZE, null));

        // remove() is the way, and a removed component is not present in either builder
        final DataComponentMap.PatchBuilder builder = DataComponentMap.patchBuilder().remove(DataComponents.MAX_STACK_SIZE);
        assertFalse(builder.has(DataComponents.MAX_STACK_SIZE));
        assertFalse(builder.build().has(DataComponents.MAX_STACK_SIZE));
        assertFalse(DataComponentMap.builder().has(DataComponents.MAX_STACK_SIZE));
    }

    @Test
    void theComponentRegistryCannotBeChangedThroughValues() {
        final int count = DataComponent.values().size();
        // A predicate removing nothing first: on a mutable view it returns quietly and leaves the registry alone
        assertThrows(UnsupportedOperationException.class, () -> DataComponent.values().removeIf(_ -> false));
        assertThrows(UnsupportedOperationException.class, () -> DataComponent.values().clear());
        assertEquals(count, DataComponent.values().size());
    }

    @Test
    void encodingAComponentWithoutACodecSaysSerialized() {
        final NullPointerException error = assertThrows(NullPointerException.class,
                () -> DataComponents.CREATIVE_SLOT_LOCK.encode(Transcoder.NBT, Unit.INSTANCE));
        assertTrue(error.getMessage().contains("cannot be serialized to Codec"), error::getMessage);
    }

    @Test
    void testBasicGet() {
        var map = DataComponentMap.patchBuilder()
                .set(DataComponents.REPAIR_COST, 10)
                .remove(DataComponents.CUSTOM_NAME)
                .build();

        assertTrue(map.has(DataComponents.REPAIR_COST));
        assertEquals(10, map.get(DataComponents.REPAIR_COST));

        assertFalse(map.has(DataComponents.CUSTOM_NAME));
        assertNull(map.get(DataComponents.CUSTOM_NAME));

        assertFalse(map.has(DataComponents.BANNER_PATTERNS));
        assertNull(map.get(DataComponents.BANNER_PATTERNS));
    }

    @Test
    void testPatchedGet() {
        var prototype = DataComponentMap.patchBuilder()
                .set(DataComponents.ITEM_NAME, Component.text("Hello"))
                .set(DataComponents.REPAIR_COST, 55)
                .set(DataComponents.CUSTOM_NAME, Component.text("World"))
                .build();
        var map = DataComponentMap.patchBuilder()
                .set(DataComponents.REPAIR_COST, 1)
                .remove(DataComponents.CUSTOM_NAME)
                .build();

        // Override
        assertTrue(map.has(prototype, DataComponents.REPAIR_COST));
        assertEquals(1, map.get(prototype, DataComponents.REPAIR_COST));

        // Inherit
        assertTrue(map.has(prototype, DataComponents.ITEM_NAME));
        assertEquals(Component.text("Hello"), map.get(prototype, DataComponents.ITEM_NAME));

        // Delete
        assertFalse(map.has(prototype, DataComponents.CUSTOM_NAME));
        assertNull(map.get(prototype, DataComponents.CUSTOM_NAME));

        // Non-existent
        assertFalse(map.has(prototype, DataComponents.BANNER_PATTERNS));
        assertNull(map.get(prototype, DataComponents.BANNER_PATTERNS));
    }

    @Test
    void testDiffEmpty() {
        var prototype = DataComponentMap.patchBuilder().set(DataComponents.REPAIR_COST, 42).build();
        var map = DataComponentMap.EMPTY;
        var diff = DataComponentMap.diff(prototype, map);

        assertNull(diff.get(DataComponents.REPAIR_COST));
    }

    @Test
    void testDiffCompleteDifference() {
        var prototype = DataComponentMap.patchBuilder().set(DataComponents.REPAIR_COST, 42).build();
        var map = DataComponentMap.patchBuilder().set(DataComponents.CUSTOM_NAME, Component.text("Hello")).build();
        var diff = DataComponentMap.diff(prototype, map);

        assertNull(diff.get(DataComponents.REPAIR_COST));
        assertEquals(Component.text("Hello"), diff.get(DataComponents.CUSTOM_NAME));
    }

    @Test
    void testDiffFlatten() {
        var prototype = DataComponentMap.builder().set(DataComponents.REPAIR_COST, 42).build();
        var map = DataComponentMap.builder().set(DataComponents.REPAIR_COST, 24).build();
        var diff = DataComponentMap.diff(prototype, map);

        assertEquals(24, diff.get(DataComponents.REPAIR_COST));
    }

    @Test
    void testBuilder() {
        var builder = DataComponentMap.builder();
        builder.set(DataComponents.REPAIR_COST, 42);

        // Builder is a getter for its own entries, so this should be valid
        assertEquals(42, builder.get(DataComponents.REPAIR_COST));
        var map1 = builder.build();
        assertEquals(42, map1.get(DataComponents.REPAIR_COST));

        // Old built map should be unaffected by change
        builder.set(DataComponents.REPAIR_COST, 24);
        var map2 = builder.build();
        assertEquals(42, map1.get(DataComponents.REPAIR_COST));
        assertEquals(24, map2.get(DataComponents.REPAIR_COST));
    }

    @Test
    void testEmptyBuildsAreCanonical() {
        assertSame(DataComponentMap.EMPTY, DataComponentMap.builder().build());
        assertSame(DataComponentMap.EMPTY, DataComponentMap.patchBuilder().build());
    }

    @Test
    void testReset() {
        var map = DataComponentMap.patchBuilder()
                .set(DataComponents.REPAIR_COST, 42)
                .remove(DataComponents.CUSTOM_NAME)
                .reset(DataComponents.REPAIR_COST)
                .build();

        assertNull(map.get(DataComponents.REPAIR_COST));
        assertFalse(map.has(DataComponents.CUSTOM_NAME));
        assertEquals(1, map.entrySet().size());
    }

    @Test
    void testApplyPatch() {
        var prototype = DataComponentMap.builder()
                .set(DataComponents.REPAIR_COST, 42)
                .set(DataComponents.CUSTOM_NAME, Component.text("Name"))
                .build();
        var patch = DataComponentMap.patchBuilder()
                .set(DataComponents.REPAIR_COST, 24)
                .remove(DataComponents.CUSTOM_NAME)
                .set(DataComponents.ITEM_NAME, Component.text("Item"))
                .build();
        var resolved = DataComponentMap.applyPatch(prototype, patch);

        assertEquals(24, resolved.get(DataComponents.REPAIR_COST));
        assertNull(resolved.get(DataComponents.CUSTOM_NAME));
        assertEquals(Component.text("Item"), resolved.get(DataComponents.ITEM_NAME));
    }
}
