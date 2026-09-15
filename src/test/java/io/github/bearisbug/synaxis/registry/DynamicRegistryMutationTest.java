package io.github.bearisbug.synaxis.registry;

import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private static int[] tagTargets(DynamicRegistryImpl<Value> registry) {
        return registry.tagRegistry().tags().getFirst().entries();
    }
}
