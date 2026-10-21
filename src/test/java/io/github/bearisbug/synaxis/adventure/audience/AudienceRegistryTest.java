package io.github.bearisbug.synaxis.adventure.audience;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudienceRegistryTest {
    private static final Key KEY = Key.key("synaxis", "test");

    private static AudienceRegistry registry() {
        return new AudienceRegistry(new ConcurrentHashMap<>(), CopyOnWriteArrayList::new);
    }

    private static Collection<? extends Audience> collection(Iterable<? extends Audience> audiences) {
        assertInstanceOf(Collection.class, audiences);
        return (Collection<? extends Audience>) audiences;
    }

    @Test
    void anUnknownKeyHasNoAudience() {
        final AudienceRegistry registry = registry();
        assertEquals(List.of(), List.copyOf(collection(registry.of(KEY))));
        assertEquals(List.of(), List.copyOf(collection(registry.all())));
        assertTrue(registry.isEmpty());
    }

    @Test
    void registeredAudiencesAreSeenUnderTheirKey() {
        final AudienceRegistry registry = registry();
        final Audience first = new Audience() {};
        final Audience second = new Audience() {};
        registry.register(KEY, first, second);

        assertEquals(List.of(first, second), List.copyOf(collection(registry.of(KEY))));
        assertEquals(List.of(first, second), List.copyOf(collection(registry.all())));
    }

    @Test
    void theAudiencesOfAKeyCannotBeChangedThroughTheView() {
        final AudienceRegistry registry = registry();
        registry.register(KEY, new Audience() {});

        final Collection<? extends Audience> known = collection(registry.of(KEY));
        assertThrows(UnsupportedOperationException.class, known::clear);
        final Collection<? extends Audience> unknown = collection(registry.of(Key.key("synaxis", "none")));
        assertThrows(UnsupportedOperationException.class, unknown::clear);
    }
}
