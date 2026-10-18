package io.github.bearisbug.synaxis.registry;

import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mutating an unfrozen registry has to leave the reverse mappings and the cached packets consistent
 * with the entries that are actually registered.
 */
public class DynamicRegistryMutationTest {

    private record Value(String name) {
    }

    @Test
    public void registeringOverAnEntryForgetsThePreviousValue() {
        final DynamicRegistry<Value> registry = DynamicRegistry.create(Key.key("synaxis:test"));
        final Value first = new Value("first");
        final Value second = new Value("second");

        final RegistryKey<Value> key = registry.register("synaxis:entry", first);
        registry.register("synaxis:entry", second);

        assertEquals(1, registry.size());
        assertEquals(key, registry.getKey(second));
        assertNull(registry.getKey(first), "The replaced value is no longer registered");
    }

    @Test
    public void removingAnEntryOutsideEveryTagStillInvalidatesTheTagsPacket() {
        final DynamicRegistryImpl<Value> registry =
                (DynamicRegistryImpl<Value>) DynamicRegistry.<Value>create(Key.key("synaxis:test"));
        registry.register("synaxis:untagged", new Value("untagged"));
        final RegistryKey<Value> tagged = registry.register("synaxis:tagged", new Value("tagged"));
        final RegistryTagImpl.Backed<Value> tag =
                (RegistryTagImpl.Backed<Value>) registry.getOrCreateTag(TagKey.unsafeOf("synaxis:tag"));
        tag.add(tagged);
        assertArrayEquals(new int[]{1}, tagTargets(registry));

        final AtomicInteger invalidations = new AtomicInteger();
        registry.tagChangeListener(invalidations::incrementAndGet);

        // The removed entry belongs to no tag, but every entry after it moves one id down
        assertTrue(registry.remove(Key.key("synaxis:untagged")));
        assertArrayEquals(new int[]{0}, tagTargets(registry));
        assertEquals(1, invalidations.get(), "The cached tags packet still points at the old ids");
    }

    /** Creating or removing a tag changes what the cached tags packet should hold just as removing an entry does. */
    @Test
    public void creatingAndRemovingATagInvalidatesTheTagsPacket() {
        final DynamicRegistryImpl<Value> registry =
                (DynamicRegistryImpl<Value>) DynamicRegistry.<Value>create(Key.key("synaxis:test"));
        final AtomicInteger invalidations = new AtomicInteger();
        registry.tagChangeListener(invalidations::incrementAndGet);
        final TagKey<Value> key = TagKey.unsafeOf("synaxis:tag");

        final RegistryTag<Value> created = registry.getOrCreateTag(key);
        assertEquals(1, invalidations.get(), "a new tag");
        assertSame(created, registry.getOrCreateTag(key));
        assertEquals(1, invalidations.get(), "an existing tag is only looked up");

        assertTrue(registry.removeTag(key));
        assertEquals(2, invalidations.get(), "a removed tag");
        assertFalse(registry.removeTag(key));
        assertEquals(2, invalidations.get(), "an absent tag is not removed");
    }

    /**
     * Once the owning process has started, tags are part of the frozen registry (API-004) like the entries: every
     * write throws {@link IllegalStateException}, naming the registry and what to do instead.
     */
    @Test
    public void aFrozenRegistryRejectsEveryWrite() {
        final DynamicRegistryImpl<Value> registry =
                (DynamicRegistryImpl<Value>) DynamicRegistry.<Value>create(Key.key("synaxis:test"));
        registry.register("synaxis:entry", new Value("entry"));
        final TagKey<Value> existing = TagKey.unsafeOf("synaxis:existing");
        final RegistryTag<Value> tag = registry.getOrCreateTag(existing);
        registry.freeze();

        assertSame(tag, registry.getOrCreateTag(existing), "an existing tag is still looked up");
        for (Executable write : List.<Executable>of(
                () -> registry.register("synaxis:other", new Value("other")),
                () -> registry.remove(Key.key("synaxis:entry")),
                () -> registry.getOrCreateTag(TagKey.unsafeOf("synaxis:new")),
                () -> registry.removeTag(existing))) {
            final String message = assertThrows(IllegalStateException.class, write).getMessage();
            assertTrue(message.contains("synaxis:test") && message.contains("before start()"), message);
        }
        assertSame(tag, registry.getTag(existing));
        assertNull(registry.getTag(TagKey.unsafeOf("synaxis:new")));
    }

    private static int[] tagTargets(DynamicRegistryImpl<Value> registry) {
        return registry.tagRegistry().tags().getFirst().entries();
    }
}
