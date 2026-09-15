package io.github.bearisbug.synaxis.item.component;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.tag.Tag;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The tag index backing {@link Tag} is a JVM wide map that never evicts, so the NBT a client sends
 * in custom data must not reach it: reading or writing one tag may only index the keys of that tag.
 */
@RegistriesTest
public class CustomDataTagIndexRegistriesTest {
    private static final int UNTRUSTED_KEYS = 4096;

    @Test
    public void getTagLeavesUntrustedKeysUnindexed() throws ReflectiveOperationException {
        final List<String> keys = untrustedKeys();
        final CustomData data = new CustomData(untrustedCompound(keys).putString("known", "value").build());
        final ItemStack item = ItemStack.of(Material.STICK).with(DataComponents.CUSTOM_DATA, data);

        assertEquals("value", data.getTag(Tag.String("known")));
        assertEquals("value", item.getTag(Tag.String("known")));
        assertNull(item.getTag(Tag.String("absent")));
        assertNotIndexed(keys);
    }

    @Test
    public void getTagOnPathLeavesUntrustedKeysUnindexed() throws ReflectiveOperationException {
        final List<String> keys = untrustedKeys();
        final CustomData data = new CustomData(CompoundBinaryTag.builder()
                .put("holder", untrustedCompound(keys).putInt("known", 5).build())
                .build());
        final ItemStack item = ItemStack.of(Material.STICK).with(DataComponents.CUSTOM_DATA, data);

        final int value = item.getTag(Tag.Integer("known").path("holder"));
        assertEquals(5, value);
        assertNull(item.getTag(Tag.Integer("known")));
        assertNull(item.getTag(Tag.Integer("known").path("absent")));
        assertNotIndexed(keys);
    }

    @Test
    public void withTagLeavesUntrustedKeysUnindexed() throws ReflectiveOperationException {
        final List<String> keys = untrustedKeys();
        final ItemStack item = ItemStack.of(Material.STICK)
                .with(DataComponents.CUSTOM_DATA, new CustomData(untrustedCompound(keys).build()));

        final ItemStack written = item.withTag(Tag.String("added"), "value");
        assertEquals("value", written.getTag(Tag.String("added")));
        assertEquals("junk", written.get(DataComponents.CUSTOM_DATA).nbt().getString(keys.getFirst()));
        assertNotIndexed(keys);
    }

    @Test
    public void typedCustomDataLeavesUntrustedKeysUnindexed() throws ReflectiveOperationException {
        final List<String> keys = untrustedKeys();
        final TypedCustomData<EntityType> data = new TypedCustomData<>(EntityType.COD,
                untrustedCompound(keys).putFloat("Health", 1.5f).build());

        final float health = data.getTag(Tag.Float("Health"));
        assertEquals(1.5f, health);
        final float written = data.withTag(Tag.Float("Health"), 2.5f).getTag(Tag.Float("Health"));
        assertEquals(2.5f, written);
        assertNotIndexed(keys);
    }

    private static List<String> untrustedKeys() {
        // Salted so that a key is new to the index no matter which tests ran before
        final String salt = UUID.randomUUID().toString();
        final List<String> keys = new ArrayList<>(UNTRUSTED_KEYS);
        for (int i = 0; i < UNTRUSTED_KEYS; i++) keys.add(salt + i);
        return keys;
    }

    private static CompoundBinaryTag.Builder untrustedCompound(List<String> keys) {
        final CompoundBinaryTag.Builder builder = CompoundBinaryTag.builder();
        for (String key : keys) builder.putString(key, "junk");
        return builder;
    }

    private static void assertNotIndexed(List<String> keys) throws ReflectiveOperationException {
        final Map<?, ?> index = tagIndex();
        final long indexed = keys.stream().filter(index::containsKey).count();
        assertEquals(0, indexed,
                () -> indexed + " of " + keys.size() + " untrusted NBT keys were given a global tag index");
    }

    private static Map<?, ?> tagIndex() throws ReflectiveOperationException {
        final Field indexMap = Class.forName("io.github.bearisbug.synaxis.tag.TagImpl").getDeclaredField("INDEX_MAP");
        indexMap.setAccessible(true);
        final Object autoIncrementMap = indexMap.get(null);
        final Field read = autoIncrementMap.getClass().getDeclaredField("read");
        read.setAccessible(true);
        return (Map<?, ?>) read.get(autoIncrementMap);
    }
}
